package dev.bilicraft.director.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/** Bounded text-only API transport. No Minecraft objects or credential-bearing diagnostics cross this boundary. */
final class AiHttpClient implements AutoCloseable {
    record Usage(long promptTokens, long completionTokens, long totalTokens, boolean reported) { }
    record Completion(String content, Usage usage, boolean usable) { }

    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("bilicraft-director-http-", 0).factory());
    private final HttpClient client = HttpClient.newBuilder().executor(executor)
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Duration timeout;

    AiHttpClient() {
        this(Duration.ofSeconds(15));
    }

    AiHttpClient(Duration timeout) {
        this.timeout = timeout;
    }

    CompletableFuture<Completion> choose(AiSettings.Provider provider, AiSettings.Profile profile,
                                         StrategyPrompt.PreparedDecision decision) {
        JsonObject request = new JsonObject();
        request.addProperty("model", profile.model);
        request.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", decision.systemPrompt());
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", decision.userJson());
        messages.add(user);
        request.add("messages", messages);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        request.add("response_format", format);
        if (provider == AiSettings.Provider.DEEPSEEK) {
            request.addProperty("max_tokens", 256);
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            request.add("thinking", thinking);
        } else {
            request.addProperty("max_completion_tokens", 256);
            // GPT-4 families are non-reasoning. Other models must explicitly accept none or fail closed.
            if (!profile.model.startsWith("gpt-4") && !profile.model.startsWith("chatgpt-4o")) {
                request.addProperty("reasoning_effort", "none");
            }
        }
        return exchange(profile, "chat/completions", request.toString(), 65_536).thenApply(body -> {
            try {
                JsonObject response = JsonParser.parseString(body).getAsJsonObject();
                JsonArray choices = response.getAsJsonArray("choices");
                if (choices.size() != 1) {
                    throw new IllegalArgumentException();
                }
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject message = choice.getAsJsonObject("message");
                JsonElement content = message.get("content");
                String text = content != null && content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()
                        ? content.getAsString() : "";
                Usage usage = new Usage(0, 0, 0, false);
                long reasoningTokens = 0;
                if (response.has("usage") && response.get("usage").isJsonObject()) {
                    JsonObject fields = response.getAsJsonObject("usage");
                    usage = new Usage(tokenCount(fields, "prompt_tokens"), tokenCount(fields, "completion_tokens"),
                            tokenCount(fields, "total_tokens"), true);
                    if (fields.has("completion_tokens_details") && fields.get("completion_tokens_details").isJsonObject()
                            && fields.getAsJsonObject("completion_tokens_details").has("reasoning_tokens")) {
                        reasoningTokens = tokenCount(fields.getAsJsonObject("completion_tokens_details"), "reasoning_tokens");
                    }
                }
                boolean usable = "stop".equals(choice.get("finish_reason").getAsString()) && reasoningTokens == 0
                        && (!message.has("reasoning_content") || message.get("reasoning_content").isJsonNull()
                        || message.get("reasoning_content").getAsString().isBlank());
                return new Completion(text, usage, usable);
            } catch (RuntimeException exception) {
                throw new java.util.concurrent.CompletionException(new IOException("AI响应格式无效"));
            }
        });
    }

    CompletableFuture<List<String>> models(AiSettings.Profile profile) {
        return exchange(profile, "models", null, 262_144).thenApply(body -> {
            try {
                JsonArray rows = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("data");
                if (rows.size() > 2_048) {
                    throw new IllegalArgumentException();
                }
                TreeSet<String> ids = new TreeSet<>();
                for (JsonElement row : rows) {
                    JsonElement id = row.getAsJsonObject().get("id");
                    if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException();
                    }
                    String value = id.getAsString();
                    if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl)
                            || (!profile.apiKey.isBlank() && value.contains(profile.apiKey))) {
                        throw new IllegalArgumentException();
                    }
                    ids.add(value);
                }
                return List.copyOf(ids);
            } catch (RuntimeException exception) {
                throw new java.util.concurrent.CompletionException(new IOException("模型列表格式无效"));
            }
        });
    }

    private CompletableFuture<String> exchange(AiSettings.Profile profile, String endpoint, String body, int limit) {
        try {
            AiSettings.validate(profile);
            if (profile.baseUrl.isBlank() || profile.apiKey.isBlank()) {
                return CompletableFuture.failedFuture(new IOException("请先保存API地址与密钥"));
            }
            String base = profile.baseUrl.replaceAll("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/" + endpoint))
                    .timeout(timeout).header("Authorization", "Bearer " + profile.apiKey)
                    .header("Accept", "application/json");
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            }
            CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request.build(), response -> new LimitedBody(limit));
            CompletableFuture<HttpResponse<byte[]>> bounded = exchange.copy()
                    .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            bounded.whenComplete((response, failure) -> {
                if (failure != null) {
                    exchange.cancel(true);
                }
            });
            return bounded.handle((response, failure) -> {
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(new IOException("AI请求超时、连接失败或响应超限"));
                }
                if (response.statusCode() != 200) {
                    throw new java.util.concurrent.CompletionException(new IOException("AI接口返回HTTP " + response.statusCode()));
                }
                return new String(response.body(), StandardCharsets.UTF_8);
            });
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new IOException("AI请求配置无效"));
        }
    }

    private static long tokenCount(JsonObject fields, String key) {
        JsonElement value = fields.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException();
        }
        long count = value.getAsBigDecimal().longValueExact();
        if (count < 0 || count > 1_000_000_000L) {
            throw new IllegalArgumentException();
        }
        return count;
    }

    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("AI响应超限"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(new IOException("AI响应读取失败"));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}

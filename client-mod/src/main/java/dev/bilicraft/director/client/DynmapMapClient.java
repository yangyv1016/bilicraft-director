package dev.bilicraft.director.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.bilicraft.director.protocol.Message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Direct external-map download only. No Minecraft state or game-server traffic is involved. */
public final class DynmapMapClient implements AutoCloseable {
    private static final int MAX_BYTES = 2 * 1_024 * 1_024;
    private static final int MAX_SCENES = 4_096;
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("bilicraft-dynmap-", 0).factory());
    private final HttpClient client;
    private final Duration timeout;
    private final long freshnessMillis;
    private final LongSupplier clock;
    private final Map<SourceKey, Cached> cache = new LinkedHashMap<>();
    private final Map<SourceKey, CompletableFuture<Result>> pending = new LinkedHashMap<>();
    private boolean closed;

    public DynmapMapClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(20), Duration.ofMinutes(5), System::currentTimeMillis);
    }

    DynmapMapClient(HttpClient client, Duration timeout, Duration freshness, LongSupplier clock) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER || timeout.isNegative() || timeout.isZero()
                || freshness.isNegative()) throw new IllegalArgumentException("Invalid map HTTP policy");
        this.client = client;
        this.timeout = timeout;
        this.freshnessMillis = freshness.toMillis();
        this.clock = clock;
    }

    public synchronized Result getCache(String baseUrl, String dynmapWorld, String worldKey) {
        Cached saved = cache.get(new SourceKey(baseUrl, dynmapWorld, worldKey));
        return saved == null ? null : saved.result();
    }

    public synchronized CompletableFuture<Result> fetch(String baseUrl, String dynmapWorld, String worldKey) {
        if (closed) return CompletableFuture.failedFuture(new IOException("地图下载器已关闭"));
        SourceKey key;
        try {
            key = new SourceKey(baseUrl, dynmapWorld, worldKey);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(new IOException("外链地图配置无效"));
        }
        Cached saved = cache.get(key);
        long now = clock.getAsLong();
        if (saved != null && now >= saved.result().fetchedAtEpochMillis()
                && now - saved.result().fetchedAtEpochMillis() < freshnessMillis) {
            return CompletableFuture.completedFuture(saved.result());
        }
        CompletableFuture<Result> existing = pending.get(key);
        if (existing != null && !existing.isDone()) return existing;
        String encodedWorld = URLEncoder.encode(key.dynmapWorld(), StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(key.baseUrl())
                        .resolve("tiles/_markers_/marker_" + encodedWorld + ".json"))
                .timeout(timeout).header("Accept", "application/json").GET();
        if (saved != null && !saved.etag().isEmpty()) request.header("If-None-Match", saved.etag());
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(request.build(), ignored -> new LimitedBody());
        CompletableFuture<HttpResponse<byte[]>> bounded = exchange.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        bounded.whenComplete((response, failure) -> { if (failure != null) exchange.cancel(true); });
        CompletableFuture<Result> result = bounded.handleAsync((response, failure) -> {
            if (failure != null) throw new CompletionException(new IOException("地图连接超时、读取失败或响应超过2MiB；保留已有缓存"));
            try {
                long fetchedAt = clock.getAsLong();
                Result fresh;
                String etag;
                if (response.statusCode() == 304 && saved != null) {
                    fresh = new Result(saved.result().scenes(), fetchedAt, "外链未变更，已确认缓存有效（HTTP 304）");
                    etag = saved.etag();
                } else if (response.statusCode() == 200) {
                    fresh = parse(response.body(), key, fetchedAt);
                    etag = response.headers().firstValue("ETag").filter(value -> value.length() <= 512
                            && value.codePoints().noneMatch(Character::isISOControl)).orElse("");
                } else {
                    throw new IOException("外链地图返回HTTP " + response.statusCode() + "；保留已有缓存");
                }
                synchronized (this) {
                    if (!closed) {
                        cache.put(key, new Cached(fresh, etag));
                        // A local client can switch sources; bound retained complete downloads to eight sources.
                        while (cache.size() > 8) cache.remove(cache.keySet().iterator().next());
                    }
                }
                return fresh;
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, executor);
        pending.put(key, result);
        result.whenComplete((value, failure) -> {
            synchronized (this) { pending.remove(key, result); }
            if (result.isCancelled()) exchange.cancel(true);
        });
        return result;
    }

    public static String scenePrefix(String baseUrl, String dynmapWorld, String worldKey) {
        SourceKey key = new SourceKey(baseUrl, dynmapWorld, worldKey);
        return "dynmap:" + digest(key.baseUrl() + "\n" + key.dynmapWorld() + "\n" + key.worldKey()).substring(0, 12) + ":";
    }

    private static Result parse(byte[] bytes, SourceKey source, long fetchedAt) throws IOException {
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonObject root;
            try (JsonReader reader = new JsonReader(new StringReader(json))) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement parsed = JsonParser.parseReader(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("地图JSON存在尾随数据；保留已有缓存");
                root = parsed.getAsJsonObject();
            }
            JsonObject sets = root.getAsJsonObject("sets");
            if (sets == null) throw new IllegalArgumentException("Missing sets");
            ArrayList<Message.SceneView> scenes = new ArrayList<>();
            int examined = 0;
            int skipped = 0;
            String prefix = scenePrefix(source.baseUrl(), source.dynmapWorld(), source.worldKey());
            for (Map.Entry<String, JsonElement> set : sets.entrySet()) {
                JsonObject contents = set.getValue().getAsJsonObject();
                for (String section : List.of("markers", "areas")) {
                    JsonObject markers = contents.has(section) ? contents.getAsJsonObject(section) : new JsonObject();
                    for (Map.Entry<String, JsonElement> marker : markers.entrySet()) {
                        if (++examined > MAX_SCENES) throw new IOException("地图标记和区域超过4096项；保留已有缓存");
                        try {
                            JsonObject fields = marker.getValue().getAsJsonObject();
                            double x;
                            double y;
                            double z;
                            double radius;
                            Message.SceneKind kind;
                            if (section.equals("markers")) {
                                x = number(fields.get("x")); y = number(fields.get("y")); z = number(fields.get("z"));
                                radius = 12;
                                kind = Message.SceneKind.VIEWPOINT;
                            } else {
                                JsonArray xs = fields.getAsJsonArray("x");
                                JsonArray zs = fields.getAsJsonArray("z");
                                if (xs.size() < 3 || xs.size() != zs.size()) throw new IllegalArgumentException("Invalid polygon");
                                double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
                                double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                                for (int index = 0; index < xs.size(); index++) {
                                    double pointX = number(xs.get(index)), pointZ = number(zs.get(index));
                                    minX = Math.min(minX, pointX); maxX = Math.max(maxX, pointX);
                                    minZ = Math.min(minZ, pointZ); maxZ = Math.max(maxZ, pointZ);
                                }
                                x = minX / 2 + maxX / 2; z = minZ / 2 + maxZ / 2;
                                y = number(fields.get("ytop")) / 2 + number(fields.get("ybottom")) / 2;
                                radius = Math.clamp(Math.min(maxX / 2 - minX / 2, maxZ / 2 - minZ / 2), 6, 48);
                                kind = Message.SceneKind.AREA;
                            }
                            JsonElement label = fields.get("label");
                            if (label == null || !label.isJsonPrimitive() || !label.getAsJsonPrimitive().isString()) {
                                throw new IllegalArgumentException("Invalid label");
                            }
                            String name = label.getAsString().replaceAll("(?is)<[^>]*>", " ")
                                    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                                    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                                    .replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").strip();
                            if (name.isEmpty()) throw new IllegalArgumentException("Empty label");
                            if (name.codePointCount(0, name.length()) > 48) name = name.substring(0, name.offsetByCodePoints(0, 48));
                            if (!StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw new IllegalArgumentException("Invalid label encoding");
                            scenes.add(new Message.SceneView(prefix + digest(set.getKey() + "\n" + section + "\n" + marker.getKey()).substring(0, 24),
                                    name, kind, source.worldKey(), x, y, z, radius));
                        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
                            skipped++;
                        }
                    }
                }
            }
            return new Result(scenes, fetchedAt, "外链导入 " + scenes.size() + " 个候选" + (skipped == 0 ? "" : "，跳过 " + skipped + " 个无效标记"));
        } catch (RuntimeException | StackOverflowError exception) {
            throw new IOException("地图JSON格式无效；保留已有缓存");
        }
    }

    private static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite number");
        return number;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime must provide SHA-256", exception);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (CompletableFuture<Result> task : List.copyOf(pending.values())) task.cancel(true);
        client.shutdownNow();
        executor.shutdownNow();
    }

    public record Result(List<Message.SceneView> scenes, long fetchedAtEpochMillis, String note) {
        public Result { scenes = List.copyOf(scenes); }
    }

    private record Cached(Result result, String etag) { }

    private record SourceKey(String baseUrl, String dynmapWorld, String worldKey) {
        SourceKey {
            URI base = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || dynmapWorld.isBlank() || worldKey.isBlank() || dynmapWorld.codePoints().anyMatch(Character::isISOControl)
                    || worldKey.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid map source");
            if (!baseUrl.endsWith("/")) baseUrl += "/";
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IOException("Map response exceeds2MiB")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(new IOException("Map response read failed")); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}

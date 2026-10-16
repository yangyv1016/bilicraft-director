package dev.bilicraft.ai.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiHttpClientTest {
    private static final String KEY = "synthetic-loopback-key-only";
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private AiSettings.Profile profile;
    private final StrategyPrompt.PreparedDecision decision = StrategyPrompt.build(List.of(
            new StrategyPrompt.Candidate("scene:world:one", StrategyPrompt.Kind.VIEWPOINT, 80, "scene")), 1, Set.of());

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        server.setExecutor(executor);
        server.start();
        profile = new AiSettings.Profile("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model", KEY);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void deepSeekSendsOnlyBoundedTextWithThinkingDisabledAndAccountsUsage() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, completion("stop", "{\"picks\":[\"c1\"]}", true));
        });
        try (AiHttpClient client = new AiHttpClient()) {
            AiHttpClient.Completion result = client.choose(AiSettings.Provider.DEEPSEEK, profile, decision).get(3, TimeUnit.SECONDS);
            assertEquals("Bearer " + KEY, authorization.get());
            assertEquals("disabled", request.get().getAsJsonObject("thinking").get("type").getAsString());
            assertEquals(256, request.get().get("max_tokens").getAsInt());
            assertFalse(request.get().get("stream").getAsBoolean());
            assertEquals(2, request.get().getAsJsonArray("messages").size());
            assertFalse(request.get().toString().contains(KEY));
            assertTrue(result.usable());
            assertEquals(List.of("scene:world:one"), decision.parseResponse(result.content()));
            assertEquals(new AiHttpClient.Usage(100, 10, 110, true), result.usage());
        }
    }

    @Test
    void openAiUsesCompletionLimitAndNoneWithoutForcingReasoningOnNonReasoningFamilies() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, completion("stop", "{\"picks\":[]}", false));
        });
        try (AiHttpClient client = new AiHttpClient()) {
            AiHttpClient.Completion result = client.choose(AiSettings.Provider.OPENAI, profile, decision).get(3, TimeUnit.SECONDS);
            assertEquals("none", request.get().get("reasoning_effort").getAsString());
            assertEquals(256, request.get().get("max_completion_tokens").getAsInt());
            assertFalse(request.get().has("max_tokens"));
            assertFalse(result.usage().reported());
            profile.model = "gpt-4.1-fixture";
            client.choose(AiSettings.Provider.OPENAI, profile, decision).get(3, TimeUnit.SECONDS);
            assertFalse(request.get().has("reasoning_effort"));
        }
    }

    @Test
    void modelsTestUsesGetAndReturnsSortedBoundedIdsWithoutAnyChatCall() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicInteger chatCalls = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            method.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"z\"}]}");
        });
        server.createContext("/v1/chat/completions", exchange -> {chatCalls.incrementAndGet();respond(exchange, 500, "{}");});
        profile.model = "";
        try (AiHttpClient client = new AiHttpClient()) {
            assertEquals(List.of("a", "z"), client.models(profile).get(3, TimeUnit.SECONDS));
            assertEquals("GET", method.get());
            assertEquals(0, chatCalls.get());
        }
    }

    @Test
    void redirectsNeverForwardAuthorizationOrFollowTheDestination() throws Exception {
        AtomicInteger destination = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            exchange.getResponseHeaders().add("Location", profile.baseUrl + "/destination");
            respond(exchange, 302, KEY);
        });
        server.createContext("/v1/destination", exchange -> {destination.incrementAndGet();respond(exchange, 200, "{}");});
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> client.models(profile).get(3, TimeUnit.SECONDS));
            assertFalse(error.toString().contains(KEY));
            assertEquals(0, destination.get());
        }
    }

    @Test
    void httpErrorsAndMalformedResponsesDoNotExposeSecretsOrRemoteBodies() throws Exception {
        server.createContext("/v1/models", exchange -> respond(exchange, 401, "credential=" + KEY));
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "not-json " + KEY));
        try (AiHttpClient client = new AiHttpClient()) {
            for (var future : List.of(client.models(profile), client.choose(AiSettings.Provider.DEEPSEEK, profile, decision))) {
                ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
                assertFalse(error.toString().contains(KEY));
                assertFalse(error.getCause().toString().contains(KEY));
            }
        }
    }

    @Test
    void chunkedOversizedResponsesAreCancelledBeforeUnboundedBuffering() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "x".repeat(65_537)));
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "x".repeat(262_145)));
        try (AiHttpClient client = new AiHttpClient()) {
            assertThrows(ExecutionException.class, () -> client.choose(AiSettings.Provider.DEEPSEEK, profile, decision).get(3, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> client.models(profile).get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void timeoutAlsoBoundsAStalledBodyAfterHeadersArrive() throws Exception {
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {Thread.sleep(1_000);} catch (InterruptedException exception) {Thread.currentThread().interrupt();}
            exchange.close();
        });
        try (AiHttpClient client = new AiHttpClient(Duration.ofMillis(150))) {
            long started = System.nanoTime();
            assertThrows(ExecutionException.class, () -> client.models(profile).get(2, TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
        }
    }

    @Test
    void truncatedOrReasoningOutputCannotBeUsedEvenWhenJsonContentLooksValid() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(completion("length", "{\"picks\":[]}", true));
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, response.get()));
        try (AiHttpClient client = new AiHttpClient()) {
            assertFalse(client.choose(AiSettings.Provider.DEEPSEEK, profile, decision).get(3, TimeUnit.SECONDS).usable());
            response.set(completion("stop", "{\"picks\":[]}", true).replace("\"message\":{", "\"message\":{\"reasoning_content\":\"not allowed\","));
            assertFalse(client.choose(AiSettings.Provider.DEEPSEEK, profile, decision).get(3, TimeUnit.SECONDS).usable());
        }
    }

    @Test
    void malformedModelIdsCannotSmuggleCredentialsIntoTheControlState() throws Exception {
        server.createContext("/v1/models", exchange -> respond(exchange, 200, "{\"data\":[{\"id\":\"" + KEY + "\"}]}"));
        try (AiHttpClient client = new AiHttpClient()) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> client.models(profile).get(3, TimeUnit.SECONDS));
            assertFalse(error.toString().contains(KEY));
        }
    }

    private static String completion(String finish, String content, boolean usage) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        return "{\"choices\":[{\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}]"
                + (usage ? ",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110}" : "") + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, 0);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

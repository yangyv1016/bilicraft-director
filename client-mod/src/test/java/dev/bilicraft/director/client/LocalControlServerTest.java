package dev.bilicraft.director.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LocalControlServerTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @AfterEach
    void closeHttpClient() {
        client.close();
    }

    @Test
    void randomLoopbackInstancesHaveIndependentTokensAndReturnStateWithoutRewritingJson() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        String json = "{\"ok\":true,\"status\":\"本地运行\"}";
        try (LocalControlServer server = new LocalControlServer(0, () -> {
            worker.set(Thread.currentThread());
            return CompletableFuture.completedFuture(json);
        }, body -> CompletableFuture.completedFuture("{}"));
             LocalControlServer other = new LocalControlServer(0, () -> CompletableFuture.completedFuture("{}"),
                     body -> CompletableFuture.completedFuture("{}"))) {
            URI address = server.address();
            assertEquals("http", address.getScheme());
            assertEquals("127.0.0.1", address.getHost());
            assertTrue(address.getPort() > 0);
            assertEquals("/", address.getPath());
            assertNull(address.getRawQuery());
            assertTrue(address.getFragment().matches("[0-9a-f]{64}"));
            assertNotEquals(address.getPort(), other.address().getPort());
            assertNotEquals(address.getFragment(), other.address().getFragment());
            HttpResponse<String> response = send(request(server, "/api/state").GET().build());
            assertEquals(200, response.statusCode());
            assertEquals(json, response.body());
            assertEquals("application/json; charset=utf-8", response.headers().firstValue("Content-Type").orElseThrow());
            assertSecurityHeaders(response);
            assertTrue(worker.get().isDaemon());
            assertTrue(worker.get().getName().startsWith("bilicraft-control-" + address.getPort() + "-"));
        }
    }

    @Test
    void anExplicitPortListensAndCanRestartWithANewSessionToken() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress("127.0.0.1", 0));
            port = reservation.getLocalPort();
        }
        URI firstAddress;
        try (LocalControlServer server = new LocalControlServer(port, () -> CompletableFuture.completedFuture("{\"run\":1}"),
                body -> CompletableFuture.completedFuture("{}"))) {
            firstAddress = server.address();
            assertEquals(port, firstAddress.getPort());
            assertEquals("{\"run\":1}", send(request(server, "/api/state").GET().build()).body());
        }
        try (LocalControlServer restarted = new LocalControlServer(port, () -> CompletableFuture.completedFuture("{\"run\":2}"),
                body -> CompletableFuture.completedFuture("{}"))) {
            assertEquals(port, restarted.address().getPort());
            assertNotEquals(firstAddress.getFragment(), restarted.address().getFragment());
            HttpRequest oldSession = HttpRequest.newBuilder(firstAddress.resolve("/api/state"))
                    .timeout(Duration.ofSeconds(6)).header("X-BiliCraft-Token", firstAddress.getFragment()).GET().build();
            assertEquals(403, send(oldSession).statusCode());
            HttpResponse<String> response = send(request(restarted, "/api/state").GET().build());
            assertEquals(200, response.statusCode());
            assertEquals("{\"run\":2}", response.body());
            assertSecurityHeaders(response);
        }
    }

    @Test
    void occupiedPreferredPortsAdvanceAndAReleasedPreferenceIsUsedOnTheNextStart() throws Exception {
        List<ServerSocket> reservations = reserveAdjacentPorts();
        int preferred = reservations.getFirst().getLocalPort();
        String shiftedToken;
        try (ServerSocket first = reservations.getFirst(); ServerSocket second = reservations.get(1);
             LocalControlServer shifted = new LocalControlServer(preferred, () -> CompletableFuture.completedFuture("{}"),
                     body -> CompletableFuture.completedFuture("{}"))) {
            assertEquals(preferred + 1, second.getLocalPort());
            assertTrue(shifted.address().getPort() >= preferred + 2);
            assertTrue(shifted.address().getPort() <= Math.min(preferred + 99, 65_535));
            shiftedToken = shifted.address().getFragment();
            assertEquals(200, send(request(shifted, "/api/state").GET().build()).statusCode());
        }
        try (LocalControlServer preferredAgain = new LocalControlServer(preferred, () -> CompletableFuture.completedFuture("{}"),
                body -> CompletableFuture.completedFuture("{}"))) {
            assertEquals(preferred, preferredAgain.address().getPort());
            assertNotEquals(shiftedToken, preferredAgain.address().getFragment());
            assertEquals(200, send(request(preferredAgain, "/api/state").GET().build()).statusCode());
        }
    }

    @Test
    void anOccupiedMaximumPortFailsWithoutWrappingToARandomPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            try {
                occupied.bind(new InetSocketAddress("127.0.0.1", 65_535));
            } catch (BindException alreadyOccupied) {
                // An existing listener exercises the same upper-bound behavior.
            }
            assertThrows(BindException.class, () -> {
                try (LocalControlServer unexpected = new LocalControlServer(65_535, () -> CompletableFuture.completedFuture("{}"),
                        body -> CompletableFuture.completedFuture("{}"))) {
                    fail("Exhausting the configured range must not start a random listener");
                }
            });
        }
    }

    @Test
    void portValidationRejectsValuesOutsideTheTcpRange() {
        for (int port : new int[]{-1, 65_536}) {
            assertThrows(IllegalArgumentException.class, () -> new LocalControlServer(port,
                    () -> CompletableFuture.completedFuture("{}"), body -> CompletableFuture.completedFuture("{}")));
        }
    }

    @Test
    void apiRequiresExactlyOneTokenHeaderAndNeverAcceptsCredentialsFromTheUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalControlServer server = new LocalControlServer(0, () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("{}");
        }, body -> CompletableFuture.completedFuture("{}"))) {
            URI state = server.address().resolve("/api/state");
            for (HttpRequest request : List.of(
                    HttpRequest.newBuilder(state).GET().build(),
                    HttpRequest.newBuilder(state).header("X-BiliCraft-Token", "secret-value-never-echo").GET().build(),
                    request(server, "/api/state").header("X-BiliCraft-Token", "duplicate").GET().build())) {
                HttpResponse<String> response = send(request);
                assertEquals(403, response.statusCode());
                assertTrue(response.body().startsWith("{\"ok\":false,\"message\":"));
                assertFalse(response.body().contains("secret-value-never-echo"));
                assertFalse(response.body().contains(server.address().getFragment()));
            }
            HttpRequest queryToken = HttpRequest.newBuilder(server.address().resolve(
                    "/api/state?token=" + server.address().getFragment())).GET().build();
            assertEquals(404, send(queryToken).statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsHostAliasesAndForeignOrDuplicateOriginsWithoutCors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalControlServer server = new LocalControlServer(0, () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("{}");
        }, body -> CompletableFuture.completedFuture("{}"))) {
            // The same local listener must reject a browser Host alias instead of accepting any loopback name.
            URI alias = URI.create("http://localhost:" + server.address().getPort() + "/api/state");
            HttpResponse<String> wrongHost = send(HttpRequest.newBuilder(alias)
                    .header("X-BiliCraft-Token", server.address().getFragment()).GET().build());
            assertEquals(403, wrongHost.statusCode());
            String origin = "http://127.0.0.1:" + server.address().getPort();
            for (String foreign : List.of("https://example.invalid", "null", origin + "/", origin + "0")) {
                HttpResponse<String> denied = send(request(server, "/api/state").header("Origin", foreign).GET().build());
                assertEquals(403, denied.statusCode());
                assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
            assertEquals(403, send(request(server, "/api/state")
                    .header("Origin", origin).header("Origin", origin).GET().build()).statusCode());
            assertEquals(0, calls.get());
            assertEquals(200, send(request(server, "/api/state").header("Origin", origin).GET().build()).statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void methodsPathsAndContentTypeAreCheckedBeforeInvokingCommands() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LocalControlServer server = new LocalControlServer(0, () -> CompletableFuture.completedFuture("{}"), body -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("{}");
        })) {
            HttpResponse<String> statePost = send(request(server, "/api/state")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            assertEquals(405, statePost.statusCode());
            assertEquals("GET", statePost.headers().firstValue("Allow").orElseThrow());
            HttpResponse<String> commandGet = send(request(server, "/api/command").GET().build());
            assertEquals(405, commandGet.statusCode());
            assertEquals("POST", commandGet.headers().firstValue("Allow").orElseThrow());
            assertEquals(405, send(request(server, "/app.js").POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode());
            for (String path : List.of("/missing", "/api/state/", "/../AiSettings.java", "/%2e%2e/app.js", "/app%2ejs")) {
                assertEquals(404, send(request(server, path).GET().build()).statusCode(), path);
            }
            assertEquals(415, send(request(server, "/api/command")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode());
            assertEquals(415, send(request(server, "/api/command").header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()).statusCode());
            assertEquals(400, send(request(server, "/api/command").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xc3, 0x28})).build()).statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void commandBodyLimitCountsUtf8BytesAndAlsoBoundsChunkedRequests() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        String json = "{\"ok\":true,\"result\":\"已保存\"}";
        try (LocalControlServer server = new LocalControlServer(0, () -> CompletableFuture.completedFuture("{}"), body -> {
            received.set(body);
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(json);
        })) {
            String exactBody = "{\"value\":\"" + "x".repeat(16_372) + "\"}";
            assertEquals(16_384, exactBody.getBytes(StandardCharsets.UTF_8).length);
            HttpResponse<String> accepted = send(request(server, "/api/command")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(exactBody)).build());
            assertEquals(200, accepted.statusCode());
            assertEquals(json, accepted.body());
            assertEquals(exactBody, received.get());
            byte[] oversized = (exactBody + " ").getBytes(StandardCharsets.UTF_8);
            assertEquals(413, send(request(server, "/api/command").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(oversized)).build()).statusCode());
            assertEquals(413, send(request(server, "/api/command").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized))).build()).statusCode());
            String multibyte = "\"" + "中".repeat(5_462) + "\"";
            assertTrue(multibyte.length() < 16_384);
            assertEquals(413, send(request(server, "/api/command").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(multibyte)).build()).statusCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void callbackFailuresReturnGenericJsonWithoutExceptionOrRequestData() throws Exception {
        String privateValue = "api-key-never-return-this";
        try (LocalControlServer server = new LocalControlServer(0,
                () -> CompletableFuture.failedFuture(new IllegalStateException(privateValue)),
                body -> { throw new IllegalArgumentException(body); })) {
            for (HttpRequest request : List.of(request(server, "/api/state").GET().build(),
                    request(server, "/api/command").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"apiKey\":\"" + privateValue + "\"}")).build())) {
                HttpResponse<String> response = send(request);
                assertEquals(503, response.statusCode());
                assertTrue(response.body().startsWith("{\"ok\":false,\"message\":"));
                assertFalse(response.body().contains(privateValue));
                assertFalse(response.body().contains("Exception"));
                assertSecurityHeaders(response);
            }
        }
    }

    @Test
    void bothCallbackTypesTimeoutCancelTheirFuturesAndReleaseTheTwoWorkers() throws Exception {
        CompletableFuture<String> state = new CompletableFuture<>();
        CompletableFuture<String> command = new CompletableFuture<>();
        AtomicReference<CompletableFuture<String>> currentState = new AtomicReference<>(state);
        CountDownLatch entered = new CountDownLatch(2);
        try (LocalControlServer server = new LocalControlServer(0, () -> {
            entered.countDown();
            return currentState.get();
        }, body -> {
            entered.countDown();
            return command;
        })) {
            long started = System.nanoTime();
            CompletableFuture<HttpResponse<String>> stateResponse = client.sendAsync(
                    request(server, "/api/state").GET().build(), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> commandResponse = client.sendAsync(request(server, "/api/command")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(1, TimeUnit.SECONDS), "Both HTTP workers must be able to wait concurrently");
            assertEquals(503, stateResponse.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(503, commandResponse.get(5, TimeUnit.SECONDS).statusCode());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 1_800);
            assertTrue(state.isCancelled());
            assertTrue(command.isCancelled());
            currentState.set(CompletableFuture.completedFuture("{\"ok\":true}"));
            assertEquals(200, send(request(server, "/api/state").GET().build()).statusCode());
        }
    }

    @Test
    void servesOnlyTheFourPackagedStaticPathsWithSecurityHeaders() throws Exception {
        try (LocalControlServer server = new LocalControlServer(0, () -> CompletableFuture.completedFuture("{}"),
                body -> CompletableFuture.completedFuture("{}"))) {
            for (String path : List.of("/", "/index.html", "/style.css", "/app.js")) {
                String file = path.equals("/") ? "index.html" : path.substring(1);
                byte[] expected;
                try (var input = LocalControlServer.class.getResourceAsStream("/assets/bilicraftdirector/control/" + file)) {
                    assertNotNull(input, "Missing actual packaged control resource " + file);
                    expected = input.readAllBytes();
                }
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(server.address().resolve(path))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode(), path);
                assertArrayEquals(expected, response.body());
                String type = response.headers().firstValue("Content-Type").orElseThrow();
                assertTrue(type.startsWith(file.endsWith("html") ? "text/html"
                        : file.endsWith("css") ? "text/css" : "text/javascript"));
                assertSecurityHeaders(response);
            }
        }
    }

    @Test
    void closeCancelsWaitingCallbacksStopsWorkersAndReleasesTheListeningPort() throws Exception {
        CompletableFuture<String> state = new CompletableFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        var existingThreads = Thread.getAllStackTraces().keySet();
        LocalControlServer server = new LocalControlServer(0, () -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            return state;
        }, body -> CompletableFuture.completedFuture("{}"));
        int port = server.address().getPort();
        try {
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                    request(server, "/api/state").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(worker.get().isDaemon());
            List<Thread> serverThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !existingThreads.contains(thread)
                            && (thread.getName().startsWith("bilicraft-control-" + port + "-")
                            || thread.getName().equals("HTTP-Dispatcher"))).toList();
            assertTrue(serverThreads.stream().anyMatch(thread -> thread.getName().equals("HTTP-Dispatcher")));
            assertTrue(serverThreads.stream().allMatch(Thread::isDaemon));
            server.close();
            server.close();
            assertTrue(state.isCancelled());
            for (Thread thread : serverThreads) {
                thread.join(1000);
                assertFalse(thread.isAlive(), thread.getName());
            }
            assertThrows(Exception.class, () -> response.get(3, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> send(request(server, "/api/state").GET().build()));
            try (ServerSocket replacement = new ServerSocket()) {
                replacement.bind(new InetSocketAddress("127.0.0.1", port));
                assertEquals(port, replacement.getLocalPort());
            }
        } finally {
            server.close();
        }
    }

    private HttpRequest.Builder request(LocalControlServer server, String path) {
        return HttpRequest.newBuilder(server.address().resolve(path)).timeout(Duration.ofSeconds(6))
                .header("X-BiliCraft-Token", server.address().getFragment());
    }

    private static List<ServerSocket> reserveAdjacentPorts() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ServerSocket first = new ServerSocket();
            first.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = first.getLocalPort();
            if (port >= 65_534) {
                first.close();
                continue;
            }
            ServerSocket second = new ServerSocket();
            try {
                second.bind(new InetSocketAddress("127.0.0.1", port + 1));
                return List.of(first, second);
            } catch (BindException occupied) {
                first.close();
                second.close();
            } catch (IOException exception) {
                first.close();
                second.close();
                throw exception;
            }
        }
        throw new IOException("Could not reserve adjacent loopback ports for the test");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertSecurityHeaders(HttpResponse<?> response) {
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        String csp = response.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertTrue(csp.contains("default-src 'self'"));
        assertTrue(csp.contains("img-src 'self' data:"));
        assertTrue(csp.contains("frame-ancestors 'none'"));
        assertFalse(csp.contains("unsafe-inline"));
        assertFalse(csp.contains("unsafe-eval"));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
}

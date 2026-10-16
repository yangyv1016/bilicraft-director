package dev.bilicraft.director.client;

import com.sun.net.httpserver.HttpServer;
import dev.bilicraft.director.protocol.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DynmapMapClientTest {
    private static final String WORLD = "minecraft:overworld";
    private static final String JSON = """
            {"sets":{"set":{"markers":{"point":{"x":10,"y":70,"z":20,"label":"<b>车站</b> &amp; 广场"}},
            "areas":{"area":{"x":[0,120,120,0],"z":[0,0,100,100],"ytop":80,"ybottom":60,"label":"城区"}}}}}
            """;
    private HttpServer server;
    private DynmapMapClient client;
    private String base;
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> body = new AtomicReference<>(JSON.getBytes(StandardCharsets.UTF_8));
    private final AtomicReference<String> condition = new AtomicReference<>();

    @BeforeEach
    void open() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/deployment/tiles/_markers_/marker_Paralon.json", exchange -> {
            calls.incrementAndGet(); condition.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("ETag", "\"map-v1\"");
            if (status.get() == 304) exchange.sendResponseHeaders(304, -1);
            else {
                byte[] bytes = body.get();
                exchange.sendResponseHeaders(status.get(), bytes.length);
                try { exchange.getResponseBody().write(bytes); } catch (IOException ignored) { /* Client deliberately cancels oversized bodies. */ }
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/deployment/";
        client = new DynmapMapClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                Duration.ofSeconds(2), Duration.ofMinutes(5), clock::get);
    }

    @AfterEach void close() { client.close(); server.stop(0); }

    @Test
    void parsesPointAreaSafeNamesStableIdsAndWorldWithoutGameApis() throws Exception {
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get(3, TimeUnit.SECONDS);
        assertEquals(2, result.scenes().size());
        Message.SceneView point = result.scenes().get(0), area = result.scenes().get(1);
        assertEquals("车站 & 广场", point.name());
        assertEquals(WORLD, point.world());
        assertEquals(Message.SceneKind.VIEWPOINT, point.kind());
        assertEquals(60, area.x()); assertEquals(50, area.z()); assertEquals(70, area.y()); assertEquals(48, area.radius());
        assertTrue(point.id().startsWith(DynmapMapClient.scenePrefix(base, "Paralon", WORLD)));
        assertEquals(DynmapMapClient.scenePrefix(base, "Paralon", WORLD), DynmapMapClient.scenePrefix(base.substring(0, base.length()-1), "Paralon", WORLD));
        assertNotEquals(DynmapMapClient.scenePrefix(base, "Paralon", WORLD), DynmapMapClient.scenePrefix(base, "Paralon", "minecraft:the_nether"));
        assertSame(result, client.getCache(base, "Paralon", WORLD));
        assertThrows(UnsupportedOperationException.class, () -> result.scenes().clear());
    }

    @Test
    void fiveMinuteCacheThenConditional304AvoidsRedownloading() throws Exception {
        DynmapMapClient.Result first = client.fetch(base, "Paralon", WORLD).get();
        clock.addAndGet(299_999);
        assertSame(first, client.fetch(base, "Paralon", WORLD).get());
        assertEquals(1, calls.get());
        clock.incrementAndGet(); status.set(304);
        DynmapMapClient.Result checked = client.fetch(base, "Paralon", WORLD).get();
        assertEquals("\"map-v1\"", condition.get());
        assertEquals(first.scenes(), checked.scenes());
        assertEquals(clock.get(), checked.fetchedAtEpochMillis());
        assertTrue(checked.note().contains("304"));
    }

    @Test
    void malformedOversizedAndHttpFailureKeepLastGoodCache() throws Exception {
        DynmapMapClient.Result first = client.fetch(base, "Paralon", WORLD).get();
        for (byte[] invalid : new byte[][]{"{".getBytes(StandardCharsets.UTF_8), new byte[2 * 1_024 * 1_024 + 1]}) {
            clock.addAndGet(300_001); body.set(invalid);
            assertThrows(Exception.class, () -> client.fetch(base, "Paralon", WORLD).get(3, TimeUnit.SECONDS));
            assertSame(first, client.getCache(base, "Paralon", WORLD));
        }
        status.set(503); body.set(JSON.getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> client.fetch(base, "Paralon", WORLD).get(3, TimeUnit.SECONDS));
        assertSame(first, client.getCache(base, "Paralon", WORLD));
    }

    @Test
    void concurrentManualRefreshSharesOneExternalRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/single/tiles/_markers_/marker_Paralon.json", exchange -> {
            calls.incrementAndGet(); received.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] bytes = JSON.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        String source = base.replace("/deployment/", "/single/");
        CompletableFuture<DynmapMapClient.Result> first = client.fetch(source, "Paralon", WORLD);
        assertTrue(received.await(1, TimeUnit.SECONDS));
        assertSame(first, client.fetch(source, "Paralon", WORLD));
        release.countDown(); first.get(3, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
    }

    @Test
    void refusesRedirectsAndDeadlineWithoutFollowingAnotherHost() throws Exception {
        server.createContext("/redirect/tiles/_markers_/marker_Paralon.json", exchange -> {
            exchange.getResponseHeaders().set("Location", base + "tiles/_markers_/marker_Paralon.json");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        assertThrows(Exception.class, () -> client.fetch(base.replace("/deployment/", "/redirect/"), "Paralon", WORLD).get(3, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
        server.createContext("/slow/tiles/_markers_/marker_Paralon.json", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            try { Thread.sleep(500); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try (DynmapMapClient shortDeadline = new DynmapMapClient(HttpClient.newHttpClient(), Duration.ofMillis(50), Duration.ZERO, clock::get)) {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> shortDeadline.fetch(base.replace("/deployment/", "/slow/"), "Paralon", WORLD).get(250, TimeUnit.MILLISECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("超时"));
        }
    }

    @Test
    void invalidMarkerSkippedButExcessMarkersOrMissingSetsRejectDownload() throws Exception {
        body.set("{\"sets\":{\"s\":{\"markers\":{\"bad\":{\"x\":\"wrong\"}}}}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, client.fetch(base, "Paralon", WORLD).get().scenes().size());
        StringBuilder large = new StringBuilder("{\"sets\":{\"s\":{\"markers\":{");
        for (int i=0;i<4097;i++) large.append(i==0 ? "" : ",").append('"').append(i).append("\":{}");
        large.append("}}}}"); body.set(large.toString().getBytes(StandardCharsets.UTF_8)); clock.addAndGet(300_001);
        assertThrows(Exception.class, () -> client.fetch(base, "Paralon", WORLD).get());
        body.set("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> client.fetch(base, "Paralon", WORLD).get());
    }

    @Test
    void isolatedSurrogateLabelDoesNotPoisonOtherValidScenes() throws Exception {
        body.set(JSON.replace("\"point\":", "\"invalid\":{\"x\":1,\"y\":70,\"z\":2,\"label\":\"\\ud800\"},\"point\":").getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(2, result.scenes().size());
        assertTrue(result.note().contains("跳过 1"));
        assertTrue(result.scenes().stream().allMatch(scene -> StandardCharsets.UTF_8.newEncoder().canEncode(scene.name())));
    }
}

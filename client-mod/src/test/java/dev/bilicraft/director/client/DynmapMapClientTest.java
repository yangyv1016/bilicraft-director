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
        assertEquals(1, result.regions().size());
        DynmapRegion region = result.regions().getFirst();
        assertEquals(area.id(), region.id());
        assertEquals(area.name(), region.name());
        assertEquals(WORLD, region.world());
        assertEquals(4, region.vertices().size());
        assertEquals(12_000, region.area());
        assertTrue(region.contains(0, 50));
        assertFalse(region.contains(121, 50));
        assertThrows(UnsupportedOperationException.class, () -> result.regions().clear());
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
        assertEquals(first.regions(), checked.regions());
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

    @Test
    void pointMarkersAndMissingAreasNeverInventCoverageForTheNearestScene() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{"station":{"x":5,"y":70,"z":5,"label":"车站"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, result.scenes().size());
        assertTrue(result.regions().isEmpty());
        assertNull(DynmapRegion.locate(result.regions(), WORLD, 5, 5));
        assertNull(DynmapRegion.locate(result.regions(), WORLD, 1_000, 1_000));
    }

    @Test
    void actualConcaveAreasPreserveGeometryAndStripHtmlColorsAndControlsFromBothLabels() throws Exception {
        body.set("""
                {"sets":{"s":{"areas":{"oldTown":{"x":[0,10,10,4,4,0],"z":[0,0,4,4,10,10],
                "ytop":100,"ybottom":60,"label":"<b>§a市区</b> &lt;i&gt;§l旧城&lt;/i&gt;\\u0001"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals("市区 旧城", result.scenes().getFirst().name());
        DynmapRegion region = result.regions().getFirst();
        assertEquals("市区 旧城", region.name());
        assertEquals(6, region.vertices().size());
        assertSame(region, DynmapRegion.locate(result.regions(), WORLD, 2, 8));
        assertSame(region, DynmapRegion.locate(result.regions(), WORLD, 4, 7));
        assertNull(DynmapRegion.locate(result.regions(), WORLD, 7, 7));
        assertNull(DynmapRegion.locate(result.regions(), "minecraft:the_nether", 2, 8));
    }

    @Test
    void alternateMinecraftColorsAndStylesAreRemovedWithoutEatingOrdinaryAmpersands() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{"island":{"x":5,"y":70,"z":5,
                "label":"<b>&amp;1翡翠岛</b> &L港口&r &amp; 广场 §a北岸 &z保留"}},
                "areas":{"town":{"x":[0,10,10,0],"z":[0,0,10,10],"ytop":80,"ybottom":60,
                "label":"&6新赫洛堡&A新区&N码头&O街道&M旧址&K路牌&R"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals("翡翠岛 港口 & 广场 北岸 &z保留", result.scenes().getFirst().name());
        assertEquals("新赫洛堡新区码头街道旧址路牌", result.scenes().getLast().name());
        assertEquals(result.scenes().getLast().name(), result.regions().getFirst().name());
    }

    @Test
    void colorOnlyLabelsAreSkippedAndNameLengthIsLimitedAfterRemovingFormatting() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{
                "empty":{"x":5,"y":70,"z":5,"label":"&1&L&r"},
                "long":{"x":6,"y":70,"z":6,"label":"%s"}}}}}
                """.formatted("&1岛".repeat(50)).getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, result.scenes().size());
        assertEquals("岛".repeat(48), result.scenes().getFirst().name());
        assertTrue(result.note().contains("跳过 1"));
    }

    @Test
    void sameNameNearbyMarkersMergeAt128BlocksButDistantPlacesAndDifferentNamesRemain() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{
                "a":{"x":0,"y":70,"z":0,"label":"&1翡翠岛"},
                "b":{"x":128,"y":70,"z":0,"label":"翡翠岛"},
                "far":{"x":1000,"y":70,"z":0,"label":"翡翠岛"},
                "other":{"x":0,"y":70,"z":0,"label":"码头"},
                "edge1":{"x":0,"y":70,"z":0,"label":"远岸"},
                "edge2":{"x":128.01,"y":70,"z":0,"label":"远岸"},
                "diag1":{"x":0,"y":70,"z":0,"label":"对角"},
                "diag2":{"x":100,"y":70,"z":100,"label":"对角"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(7, result.scenes().size());
        assertEquals(2, result.scenes().stream().filter(scene -> scene.name().equals("翡翠岛")).count());
        assertTrue(result.scenes().stream().anyMatch(scene -> scene.name().equals("翡翠岛") && scene.x() == 1000));
        assertTrue(result.scenes().stream().anyMatch(scene -> scene.name().equals("码头")));
        assertEquals(2, result.scenes().stream().filter(scene -> scene.name().equals("远岸")).count());
        assertEquals(2, result.scenes().stream().filter(scene -> scene.name().equals("对角")).count());
        assertTrue(result.note().contains("合并 1"));
    }

    @Test
    void filteringSceneCandidatesKeepsAllAreaPolygonsAndDistinctSceneKinds() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{"point":{"x":5,"y":70,"z":5,"label":"城区"}},"areas":{
                "a":{"x":[0,10,10,0],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"城区"},
                "b":{"x":[10,20,20,10],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"城区"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(2, result.scenes().size());
        assertEquals(1, result.scenes().stream().filter(scene -> scene.kind() == Message.SceneKind.VIEWPOINT).count());
        assertEquals(1, result.scenes().stream().filter(scene -> scene.kind() == Message.SceneKind.AREA).count());
        assertEquals(2, result.regions().size());
        assertNotNull(DynmapRegion.locate(result.regions(), WORLD, 5, 5));
        assertNotNull(DynmapRegion.locate(result.regions(), WORLD, 15, 5));
        assertTrue(result.regions().stream().anyMatch(region -> region.id().equals(result.scenes().getLast().id())));
    }

    @Test
    void representativeIdAndCoordinatesStayStableWhenMarkerOrderChanges() throws Exception {
        String first = "\"a\":{\"x\":0,\"y\":70,\"z\":0,\"label\":\"车站\"}";
        String second = "\"b\":{\"x\":10,\"y\":80,\"z\":10,\"label\":\"车站\"}";
        body.set(("{\"sets\":{\"s\":{\"markers\":{" + first + "," + second + "}}}}").getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result original = client.fetch(base, "Paralon", WORLD).get();
        clock.addAndGet(300_001);
        body.set(("{\"sets\":{\"s\":{\"markers\":{" + second + "," + first + "}}}}").getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result reordered = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, original.scenes().size());
        assertEquals(original.scenes(), reordered.scenes());
        assertTrue(original.scenes().getFirst().x() == 0 || original.scenes().getFirst().x() == 10);
    }

    @Test
    void bilicraftHtmlWrappersImportOnlyTheVisibleTitleWithoutCssOrOwnerTooltips() throws Exception {
        body.set("""
                {"sets":{"markers":{"markers":{"view":{"x":1,"y":70,"z":2,
                "label":"<div class=\\"main\\"><div>&1世界树</div><style>.toptip{display:none;}</style><div class=\\"toptip\\"><div>创建时间：2026-10-16</div><div>玩家：fixture-owner</div></div></div>"},
                "plain":{"x":500,"y":70,"z":2,"label":"<style>.hidden{color:red;}</style><b>公会大厅</b>"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals("世界树", result.scenes().getFirst().name());
        assertEquals("公会大厅", result.scenes().getLast().name());
    }

    @Test
    void pluginIdentifiersAreBlockedWithoutHidingEnglishPlaceNamesOrEntireLayers() throws Exception {
        body.set("""
                {"sets":{
                "residence.markerset":{"areas":{
                "internal":{"x":[0,10,10,0],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"newhoroburgh.market_g7"},
                "internal2":{"x":[10,20,20,10],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"newhoroburgh.NH5-D22-W4"},
                "normal":{"x":[500,510,510,500],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"Elysian_Realm"}}},
                "factions_markerset":{"areas":{"town":{"x":[900,910,910,900],"z":[0,0,10,10],"ytop":80,"ybottom":60,"label":"Noah"}}},
                "stargate":{"markers":{"portal":{"x":1200,"y":70,"z":0,"label":"World Tree"}}},
                "markers":{"markers":{"station":{"x":1500,"y":70,"z":0,"label":"[DRT]云轨冬泉谷站"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(java.util.Set.of("Elysian_Realm", "Noah", "World Tree", "[DRT]云轨冬泉谷站"),
                new java.util.HashSet<>(result.scenes().stream().map(Message.SceneView::name).toList()));
        assertEquals(4, result.regions().size(), "candidate filtering must not remove source geometry");
        assertNotNull(DynmapRegion.locate(result.regions(), WORLD, 5, 5));
        assertTrue(result.note().contains("屏蔽 2"));
    }

    @Test
    void railwayPolicyRejectsOnlyExplicitTechnicalNamesAndLegacyCssFragments() {
        for (String blocked : java.util.List.of("西线 K12+345", "K0+001", "国铁里程标12", "线路节点_4",
                "轨道节点", "铁路控制点-20", "信号机#3", "道岔12", "rail_node_19", "track:42", "signal_3",
                "newhoroburgh.market_g7", "newhoroburgh.apartment1.qmswyx",
                "冰克洛-瓦尔德分部 .toptip{display:none;}.main{display:in")) {
            assertFalse(DynmapMapClient.eligibleName(blocked), blocked);
        }
        for (String kept : java.util.List.of("[国铁]泽地站", "[私铁][SF2]金色平原站", "[DRT]暖风河谷",
                "湖光冰道霜湖谷站", "千霄草线 西州湾站", "新赫洛堡火车总站", "铁路博物馆", "信号机展览馆",
                "Noah", "HESCO超市", "Elysian_Realm", "The_Spirit_Tree", "Modern Art Museum", "St. Paul's Cathedral",
                "冰克洛-瓦尔德分部")) {
            assertTrue(DynmapMapClient.eligibleName(kept), kept);
        }
    }

    @Test
    void railwayTechnicalCandidatesAreFilteredButStationsRemain() throws Exception {
        body.set("""
                {"sets":{"markers":{"markers":{
                "signal":{"x":1,"y":70,"z":2,"label":"信号机#3"},
                "mileage":{"x":500,"y":70,"z":2,"label":"西线 K12+345"},
                "station":{"x":1000,"y":70,"z":2,"label":"[国铁]泽地站"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, result.scenes().size());
        assertEquals("[国铁]泽地站", result.scenes().getFirst().name());
        assertTrue(result.note().contains("屏蔽 2"));
    }

    @Test
    void generatedWorldBorderIsNotASceneButACustomLandmarkWithThatNameIsKept() throws Exception {
        body.set("""
                {"sets":{"markers":{"markers":{"landmark":{"x":5,"y":70,"z":5,"label":"世界边界"}},"areas":{
                "_worldborder_Paralon":{"x":[0,1000,1000,0],"z":[0,0,1000,1000],"ytop":80,"ybottom":60,"label":"世界边界"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, result.scenes().size());
        assertEquals(Message.SceneKind.VIEWPOINT, result.scenes().getFirst().kind());
        assertEquals(1, result.regions().size());
        assertTrue(result.note().contains("屏蔽 1"));
    }

    @Test
    void malformedAndDegenerateAreasAreSkippedWithoutPoisoningValidPointMarkers() throws Exception {
        body.set("""
                {"sets":{"s":{"markers":{"point":{"x":1,"y":70,"z":2,"label":"保留景点"}},"areas":{
                "mismatch":{"x":[0,10,0],"z":[0,10],"ytop":80,"ybottom":60,"label":"无效"},
                "line":{"x":[0,1,2],"z":[0,1,2],"ytop":80,"ybottom":60,"label":"直线"},
                "infinite":{"x":[0,1e999,0],"z":[0,0,10],"ytop":80,"ybottom":60,"label":"无效"}}}}}
                """.getBytes(StandardCharsets.UTF_8));
        DynmapMapClient.Result result = client.fetch(base, "Paralon", WORLD).get();
        assertEquals(1, result.scenes().size());
        assertEquals("保留景点", result.scenes().getFirst().name());
        assertTrue(result.regions().isEmpty());
        assertTrue(result.note().contains("跳过 3"));
    }
}

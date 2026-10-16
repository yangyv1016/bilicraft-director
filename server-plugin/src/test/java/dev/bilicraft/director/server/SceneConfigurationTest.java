package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Message.SceneKind;
import dev.bilicraft.director.protocol.Message.SceneView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SceneConfigurationTest {
    private static final SceneConfiguration.Bounds BORDER = new SceneConfiguration.Bounds(-100, 100, -100, 100);

    @Test
    void loadsViewpointAndAreaInConfiguredWorldAndKeepsListImmutable() {
        Map<String, Object> area = entry("district", 30, 30, 12);
        area.put("kind", "area");
        List<SceneView> scenes = parse(List.of(entry("station", -30, -30, 12), area), List.of());
        assertEquals(2, scenes.size());
        assertEquals(SceneKind.VIEWPOINT, scenes.getFirst().kind());
        assertEquals(SceneKind.AREA, scenes.getLast().kind());
        assertEquals("world", scenes.getFirst().world());
        assertEquals(-30, scenes.getFirst().x());
        assertThrows(UnsupportedOperationException.class, scenes::clear);
        assertTrue(parse(List.of(), List.of()).isEmpty());
    }

    @Test
    void rejectsDuplicateIdsAndOversizedCatalogButAccepts64Entries() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            entries.add(entry("scene-" + index, 0, 0, 12));
        }
        assertEquals(64, parse(entries, List.of()).size());
        entries.add(entry("scene-64", 0, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> parse(entries, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("same", 0, 0, 12), entry("same", 30, 30, 12)), List.of()));
    }

    @Test
    void validatesUtf8ByteLimitsRatherThanJustCharacterCounts() {
        Map<String, Object> valid = entry("景".repeat(21), 0, 0, 12);
        valid.put("name", "景".repeat(85));
        assertEquals(1, parse(List.of(valid), List.of()).size());
        valid.put("id", "景".repeat(22));
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(valid), List.of()));
        valid.put("id", "normal");
        valid.put("name", "景".repeat(86));
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(valid), List.of()));
    }

    @Test
    void rejectsMalformedTextBeforeItCanFailNetworkEncoding() {
        for (String field : List.of("id", "name")) {
            for (String value : List.of("", "   ", "line\nfeed", "\ud800")) {
                Map<String, Object> invalid = entry("normal", 0, 0, 12);
                invalid.put(field, value);
                assertThrows(IllegalArgumentException.class, () -> parse(List.of(invalid), List.of()));
            }
        }
        Map<String, Object> paddedId = entry(" padded ", 0, 0, 12);
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(paddedId), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SceneConfiguration.parse(List.of(), "界".repeat(86), BORDER, List.of(), -64, 320));
    }

    @Test
    void rejectsWrongShapeUnknownFieldsAndUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> parse("not a list", List.of()));
        assertThrows(IllegalArgumentException.class, () -> parse(List.of("not a scene"), List.of()));
        Map<String, Object> extra = entry("test", 0, 0, 12);
        extra.put("world", "another-world");
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(extra), List.of()));
        Map<String, Object> missing = entry("test", 0, 0, 12);
        missing.remove("radius");
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(missing), List.of()));
        Map<String, Object> kind = entry("test", 0, 0, 12);
        kind.put("kind", "player");
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(kind), List.of()));
        Map<String, Object> coordinate = entry("test", 0, 0, 12);
        coordinate.put("x", "0");
        assertThrows(IllegalArgumentException.class, () -> parse(List.of(coordinate), List.of()));
    }

    @Test
    void rejectsNonFiniteCoordinatesAndRadius() {
        for (String field : List.of("x", "y", "z", "radius")) {
            for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                Map<String, Object> invalid = entry("test", 0, 0, 12);
                invalid.put(field, value);
                assertThrows(IllegalArgumentException.class, () -> parse(List.of(invalid), List.of()));
            }
        }
    }

    @Test
    void acceptsRadiusEndpointsAndRejectsOutsideRange() {
        assertEquals(1, parse(List.of(entry("small", 0, 0, 6)), List.of()).size());
        assertEquals(1, parse(List.of(entry("large", 0, 0, 48)), List.of()).size());
        for (double radius : new double[]{5.999, 48.001, -12}) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(List.of(entry("invalid", 0, 0, radius)), List.of()));
        }
    }

    @Test
    void leavesFourBlocksAboveCenterForClientCameraHeight() {
        Map<String, Object> scene = entry("test", 0, 0, 12);
        for (double height : new double[]{-64, 315}) {
            scene.put("y", height);
            assertEquals(1, parse(List.of(scene), List.of()).size());
        }
        for (double height : new double[]{-64.001, 315.001}) {
            scene.put("y", height);
            assertThrows(IllegalArgumentException.class, () -> parse(List.of(scene), List.of()));
        }
    }

    @Test
    void footprintMustStayStrictlyInsideWorldBorder() {
        assertEquals(1, parse(List.of(entry("inside", 87.9, 0, 12)), List.of()).size());
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("touching", 88, 0, 12)), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("outside", -89, 0, 12)), List.of()));
    }

    @Test
    void rejectsFootprintTouchingOrContainingExcludedRegionEvenWhenCenterIsOutside() {
        List<SceneConfiguration.Bounds> exclusions = List.of(new SceneConfiguration.Bounds(10, 15, -5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("touching", -2, 0, 12)), exclusions));
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("overlapping", 0, 0, 12)), exclusions));
        assertThrows(IllegalArgumentException.class,
                () -> parse(List.of(entry("containing", 0, 0, 24)), exclusions));
        assertEquals(1, parse(List.of(entry("clear", -2.1, 0, 12)), exclusions).size());
    }

    @Test
    void snapshotCanRecheckAWorldBorderThatShrankAfterStartup() {
        SceneView scene = parse(List.of(entry("scene", 40, 0, 12)), List.of()).getFirst();
        assertTrue(SceneConfiguration.fitsPatrolArea(scene, BORDER, List.of()));
        assertFalse(SceneConfiguration.fitsPatrolArea(scene,
                new SceneConfiguration.Bounds(-50, 50, -50, 50), List.of()));
    }

    @Test
    void invalidRectangleNeverBecomesAnAllowedExclusion() {
        assertThrows(IllegalArgumentException.class, () -> new SceneConfiguration.Bounds(1, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneConfiguration.Bounds(0, 1, Double.NaN, 1));
    }

    private List<SceneView> parse(Object entries, List<SceneConfiguration.Bounds> exclusions) {
        return SceneConfiguration.parse(entries, "world", BORDER, exclusions, -64, 320);
    }

    private Map<String, Object> entry(String id, double x, double z, double radius) {
        return new HashMap<>(Map.of("id", id, "name", "取景 " + id, "kind", "viewpoint",
                "x", x, "y", 64, "z", z, "radius", radius));
    }
}

package dev.bilicraft.director.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynmapRegionTest {
    private static final String WORLD = "minecraft:overworld";

    @Test
    void concavePolygonUsesActualEdgesIncludingBoundaryInsteadOfItsBoundingBox() {
        DynmapRegion region = new DynmapRegion("town", "城区", WORLD, List.of(
                new DynmapRegion.Vertex(0, 0), new DynmapRegion.Vertex(10, 0),
                new DynmapRegion.Vertex(10, 4), new DynmapRegion.Vertex(4, 4),
                new DynmapRegion.Vertex(4, 10), new DynmapRegion.Vertex(0, 10)));
        assertEquals(64, region.area());
        assertTrue(region.contains(2, 8));
        assertTrue(region.contains(8, 2));
        assertTrue(region.contains(4, 7));
        assertTrue(region.contains(10, 4));
        assertFalse(region.contains(7, 7));
        assertFalse(region.contains(10.001, 2));
        assertFalse(region.contains(Double.NaN, 2));
    }

    @Test
    void slantedEdgesAndLargeCoordinatesWorkInEitherVertexOrder() {
        double offset = 29_000_000;
        ArrayList<DynmapRegion.Vertex> vertices = new ArrayList<>(List.of(
                new DynmapRegion.Vertex(offset, -offset), new DynmapRegion.Vertex(offset + 9, -offset),
                new DynmapRegion.Vertex(offset, -offset + 9)));
        DynmapRegion first = new DynmapRegion("triangle", "三角城区", WORLD, vertices);
        Collections.reverse(vertices);
        DynmapRegion reversed = new DynmapRegion("reverse", "反向城区", WORLD, vertices);
        for (DynmapRegion region : List.of(first, reversed)) {
            assertEquals(40.5, region.area());
            assertTrue(region.contains(offset + 4.5, -offset + 4.5));
            assertTrue(region.contains(offset + 1, -offset + 1));
            assertFalse(region.contains(offset + 6, -offset + 6));
        }
        vertices.clear();
        assertEquals(3, first.vertices().size());
        assertThrows(UnsupportedOperationException.class, () -> first.vertices().clear());
    }

    @Test
    void overlappingRegionsSelectTheSmallestActualAreaInTheCurrentWorld() {
        DynmapRegion broad = new DynmapRegion("district", "大区", WORLD, List.of(
                new DynmapRegion.Vertex(0, 0), new DynmapRegion.Vertex(20, 0),
                new DynmapRegion.Vertex(20, 20), new DynmapRegion.Vertex(0, 20)));
        DynmapRegion triangle = new DynmapRegion("town", "城区", WORLD, List.of(
                new DynmapRegion.Vertex(0, 0), new DynmapRegion.Vertex(12, 0), new DynmapRegion.Vertex(0, 12)));
        DynmapRegion small = new DynmapRegion("street", "街区", WORLD, List.of(
                new DynmapRegion.Vertex(1, 1), new DynmapRegion.Vertex(3, 1),
                new DynmapRegion.Vertex(3, 3), new DynmapRegion.Vertex(1, 3)));
        DynmapRegion otherWorld = new DynmapRegion("other", "异世界", "minecraft:the_nether", List.of(
                new DynmapRegion.Vertex(1.5, 1.5), new DynmapRegion.Vertex(2.5, 1.5),
                new DynmapRegion.Vertex(2.5, 2.5), new DynmapRegion.Vertex(1.5, 2.5)));
        List<DynmapRegion> regions = List.of(otherWorld, broad, small, triangle);
        assertSame(small, DynmapRegion.locate(regions, WORLD, 2, 2));
        assertSame(triangle, DynmapRegion.locate(regions, WORLD, 8, 2));
        assertSame(broad, DynmapRegion.locate(regions, WORLD, 15, 15));
        assertSame(otherWorld, DynmapRegion.locate(regions, "minecraft:the_nether", 2, 2));
        assertNull(DynmapRegion.locate(regions, WORLD, 20.1, 2));
        assertNull(DynmapRegion.locate(List.of(), WORLD, 2, 2));
    }

    @Test
    void equalAreasHaveStableInputOrderAndClosedPolygonRepeatingFirstVertexIsAccepted() {
        List<DynmapRegion.Vertex> vertices = List.of(new DynmapRegion.Vertex(-5, -5),
                new DynmapRegion.Vertex(5, -5), new DynmapRegion.Vertex(5, 5),
                new DynmapRegion.Vertex(-5, 5), new DynmapRegion.Vertex(-5, -5));
        DynmapRegion first = new DynmapRegion("first", "先出现", WORLD, vertices);
        DynmapRegion second = new DynmapRegion("second", "后出现", WORLD, vertices);
        assertEquals(100, first.area());
        assertSame(first, DynmapRegion.locate(List.of(first, second), WORLD, 0, 0));
        assertSame(second, DynmapRegion.locate(List.of(second, first), WORLD, -5, -5));
    }

    @Test
    void nonfiniteVerticesTooFewPointsAndZeroAreaCannotBecomeRegions() {
        assertThrows(IllegalArgumentException.class, () -> new DynmapRegion.Vertex(Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new DynmapRegion.Vertex(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new DynmapRegion("few", "不足", WORLD,
                List.of(new DynmapRegion.Vertex(0, 0), new DynmapRegion.Vertex(1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new DynmapRegion("line", "直线", WORLD,
                List.of(new DynmapRegion.Vertex(0, 0), new DynmapRegion.Vertex(1, 1), new DynmapRegion.Vertex(2, 2))));
        assertThrows(IllegalArgumentException.class, () -> new DynmapRegion("overflow", "过大", WORLD,
                List.of(new DynmapRegion.Vertex(-Double.MAX_VALUE, 0), new DynmapRegion.Vertex(Double.MAX_VALUE, 0),
                        new DynmapRegion.Vertex(0, Double.MAX_VALUE))));
    }
}

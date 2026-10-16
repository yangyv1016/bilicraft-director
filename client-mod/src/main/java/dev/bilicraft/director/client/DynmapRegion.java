package dev.bilicraft.director.client;

import java.util.List;

/** Actual Dynmap area geometry. Point markers are never coverage regions. */
public final class DynmapRegion {
    private final String id;
    private final String name;
    private final String world;
    private final List<Vertex> vertices;
    private final double minX;
    private final double minZ;
    private final double maxX;
    private final double maxZ;
    private final double area;

    public DynmapRegion(String id, String name, String world, List<Vertex> vertices) {
        this.id = id;
        this.name = name;
        this.world = world;
        vertices = List.copyOf(vertices);
        this.vertices = vertices;
        if (vertices.size() < 3) throw new IllegalArgumentException("Region requires at least three vertices");
        double minimumX = Double.POSITIVE_INFINITY, minimumZ = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY, maximumZ = Double.NEGATIVE_INFINITY;
        for (Vertex vertex : vertices) {
            minimumX = Math.min(minimumX, vertex.x()); maximumX = Math.max(maximumX, vertex.x());
            minimumZ = Math.min(minimumZ, vertex.z()); maximumZ = Math.max(maximumZ, vertex.z());
        }
        minX = minimumX; minZ = minimumZ; maxX = maximumX; maxZ = maximumZ;
        Vertex origin = vertices.getFirst();
        double twiceArea = 0;
        for (int index = 1; index < vertices.size() - 1; index++) {
            Vertex first = vertices.get(index), second = vertices.get(index + 1);
            twiceArea += (first.x() - origin.x()) * (second.z() - origin.z())
                    - (second.x() - origin.x()) * (first.z() - origin.z());
        }
        area = Math.abs(twiceArea) / 2;
        if (!Double.isFinite(area) || area == 0) throw new IllegalArgumentException("Region requires finite nonzero area");
    }

    public String id() { return id; }
    public String name() { return name; }
    public String world() { return world; }
    public List<Vertex> vertices() { return vertices; }
    public double area() { return area; }

    public boolean contains(double x, double z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        boolean inside = false;
        for (int index = 0, previous = vertices.size() - 1; index < vertices.size(); previous = index++) {
            Vertex first = vertices.get(previous), second = vertices.get(index);
            double firstProduct = (x - first.x()) * (second.z() - first.z());
            double secondProduct = (z - first.z()) * (second.x() - first.x());
            double tolerance = 4 * Math.ulp(Math.max(Math.abs(firstProduct), Math.abs(secondProduct)));
            if (Math.abs(firstProduct - secondProduct) <= tolerance
                    && x >= Math.min(first.x(), second.x()) && x <= Math.max(first.x(), second.x())
                    && z >= Math.min(first.z(), second.z()) && z <= Math.max(first.z(), second.z())) return true;
            if ((first.z() > z) != (second.z() > z)
                    && x < (second.x() - first.x()) * (z - first.z()) / (second.z() - first.z()) + first.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Returns the smallest area containing the camera, or null when no actual polygon covers it. */
    public static DynmapRegion locate(List<DynmapRegion> regions, String worldKey, double x, double z) {
        DynmapRegion selected = null;
        for (DynmapRegion region : regions) {
            if (region.world().equals(worldKey) && region.contains(x, z)
                    && (selected == null || region.area() < selected.area())) selected = region;
        }
        return selected;
    }

    public record Vertex(double x, double z) {
        public Vertex {
            if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("Non-finite region vertex");
        }
    }
}

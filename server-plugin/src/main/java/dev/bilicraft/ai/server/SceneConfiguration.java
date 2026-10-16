package dev.bilicraft.ai.server;

import dev.bilicraft.ai.protocol.Message.SceneKind;
import dev.bilicraft.ai.protocol.Message.SceneView;
import dev.bilicraft.ai.protocol.Protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates configured scenery and its complete local camera footprint without loading chunks. */
final class SceneConfiguration {
    private static final Set<String> FIELDS = Set.of("id", "name", "kind", "x", "y", "z", "radius");

    private SceneConfiguration() {
    }

    static List<SceneView> parse(Object configured, String world, Bounds worldBorder,
                                 List<Bounds> exclusions, int minHeight, int maxHeight) {
        return activate(parseEntries(configured), world, worldBorder, exclusions, minHeight, maxHeight);
    }

    static List<ConfiguredScene> parseEntries(Object configured) {
        if (!(configured instanceof List<?> entries) || entries.size() > Protocol.MAX_SCENES) {
            throw new IllegalArgumentException("scenes 必须是列表，最多 " + Protocol.MAX_SCENES + " 项");
        }
        List<ConfiguredScene> scenes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> values) || !values.keySet().equals(FIELDS)
                    || !(values.get("id") instanceof String id) || id.isBlank() || !id.equals(id.strip())
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(id)
                    || id.getBytes(StandardCharsets.UTF_8).length > 64 || id.codePoints().anyMatch(Character::isISOControl)
                    || !(values.get("name") instanceof String name) || name.isBlank()
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(name)
                    || name.getBytes(StandardCharsets.UTF_8).length > 256 || name.codePoints().anyMatch(Character::isISOControl)
                    || !(values.get("kind") instanceof String kind)
                    || !(values.get("x") instanceof Number x) || !(values.get("y") instanceof Number y)
                    || !(values.get("z") instanceof Number z) || !(values.get("radius") instanceof Number radius)) {
                throw new IllegalArgumentException("scenes 每项仅允许 id/name/kind/x/y/z/radius；文本不能为空或含控制字符，id/name 最多 64/256 UTF-8 字节");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("scenes id 重复：" + id);
            }
            SceneKind sceneKind = switch (kind) {
                case "viewpoint" -> SceneKind.VIEWPOINT;
                case "area" -> SceneKind.AREA;
                default -> throw new IllegalArgumentException("scenes " + id + " 的 kind 必须是 viewpoint 或 area");
            };
            if (!Double.isFinite(x.doubleValue()) || !Double.isFinite(y.doubleValue())
                    || !Double.isFinite(z.doubleValue()) || !Double.isFinite(radius.doubleValue())
                    || radius.doubleValue() < 6 || radius.doubleValue() > 48) {
                throw new IllegalArgumentException("scenes " + id + " 坐标必须有限、radius 必须为 6～48");
            }
            scenes.add(new ConfiguredScene(id, name, sceneKind,
                    x.doubleValue(), y.doubleValue(), z.doubleValue(), radius.doubleValue()));
        }
        return List.copyOf(scenes);
    }

    static List<Bounds> parseExclusions(Object configured) {
        if (!(configured instanceof List<?> entries)) {
            throw new IllegalArgumentException("exclude-regions 必须是矩形列表，或空列表 []");
        }
        List<Bounds> exclusions = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> region)
                    || !(region.get("min-x") instanceof Number minX) || !(region.get("max-x") instanceof Number maxX)
                    || !(region.get("min-z") instanceof Number minZ) || !(region.get("max-z") instanceof Number maxZ)
                    || !region.keySet().equals(Set.of("min-x", "max-x", "min-z", "max-z"))) {
                throw new IllegalArgumentException("exclude-regions 每项仅允许数值 min-x/max-x/min-z/max-z");
            }
            exclusions.add(new Bounds(minX.doubleValue(), maxX.doubleValue(), minZ.doubleValue(), maxZ.doubleValue()));
        }
        return List.copyOf(exclusions);
    }

    static List<SceneView> activate(List<ConfiguredScene> configured, String dimensionKey, Bounds worldBorder,
                                    List<Bounds> exclusions, int minHeight, int maxHeight) {
        if (dimensionKey.isBlank() || !StandardCharsets.UTF_8.newEncoder().canEncode(dimensionKey)
                || dimensionKey.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("世界 dimension key 的 UTF-8 长度必须为 1～256 字节");
        }
        List<SceneView> scenes = new ArrayList<>();
        for (ConfiguredScene configuredScene : configured) {
            if (configuredScene.y < minHeight || configuredScene.y > maxHeight - 5) {
                throw new IllegalArgumentException("scenes " + configuredScene.id + " 的 y 须在 "
                        + minHeight + "～" + (maxHeight - 5) + " 之间，为机位保留上方 4 格");
            }
            SceneView scene = new SceneView(configuredScene.id, configuredScene.name, configuredScene.kind, dimensionKey,
                    configuredScene.x, configuredScene.y, configuredScene.z, configuredScene.radius);
            if (!fitsPatrolArea(scene, worldBorder, exclusions)) {
                throw new IllegalArgumentException("scenes " + scene.id() + " 的中心±半径包围矩形接触排除区或世界边界");
            }
            scenes.add(scene);
        }
        return List.copyOf(scenes);
    }

    record ConfiguredScene(String id, String name, SceneKind kind, double x, double y, double z, double radius) {
    }

    static boolean fitsPatrolArea(SceneView scene, Bounds worldBorder, List<Bounds> exclusions) {
        double minX = scene.x() - scene.radius();
        double maxX = scene.x() + scene.radius();
        double minZ = scene.z() - scene.radius();
        double maxZ = scene.z() + scene.radius();
        if (minX <= worldBorder.minX || maxX >= worldBorder.maxX
                || minZ <= worldBorder.minZ || maxZ >= worldBorder.maxZ) {
            return false;
        }
        for (Bounds excluded : exclusions) {
            if (maxX >= excluded.minX && minX <= excluded.maxX
                    && maxZ >= excluded.minZ && minZ <= excluded.maxZ) {
                return false;
            }
        }
        return true;
    }

    record Bounds(double minX, double maxX, double minZ, double maxZ) {
        Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                    || minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("区域边界必须有限，且最小值不能大于最大值");
            }
        }
    }
}

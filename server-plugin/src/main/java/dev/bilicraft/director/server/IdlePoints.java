package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Message;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One shared fixed camera pose per persistent server world UUID. */
final class IdlePoints {
    private final Path file;
    private final Map<UUID, Message.IdlePose> points = new LinkedHashMap<>();

    IdlePoints(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) return;
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file.toFile());
            if (!Integer.valueOf(1).equals(configuration.get("version"))
                    || !configuration.getKeys(false).equals(Set.of("version", "worlds"))) {
                throw new IllegalArgumentException("idle-points.yml 须包含 version: 1 和 worlds 映射");
            }
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null) throw new IllegalArgumentException("worlds 必须为 UUID 映射，空映射使用 {}");
            for (String key : worlds.getKeys(false)) {
                UUID id = UUID.fromString(key);
                ConfigurationSection entry = worlds.getConfigurationSection(key);
                if (!id.toString().equalsIgnoreCase(key) || entry == null
                        || !entry.getKeys(false).equals(Set.of("x", "y", "z", "yaw", "pitch"))
                        || !(entry.get("x") instanceof Number x) || !(entry.get("y") instanceof Number y)
                        || !(entry.get("z") instanceof Number z) || !(entry.get("yaw") instanceof Number yaw)
                        || !(entry.get("pitch") instanceof Number pitch)) {
                    throw new IllegalArgumentException("挂机点须以规范世界 UUID 为键，仅包含数值 x/y/z/yaw/pitch");
                }
                Message.IdlePose point = new Message.IdlePose(x.doubleValue(), y.doubleValue(), z.doubleValue(), yaw.floatValue(), pitch.floatValue());
                if (points.putIfAbsent(id, point) != null) throw new IllegalArgumentException("重复挂机点世界 UUID");
            }
        } catch (InvalidConfigurationException | IllegalArgumentException exception) {
            throw new IOException("挂机点读取失败：" + file, exception);
        }
    }

    Message.IdlePose get(UUID world) {
        return points.get(world);
    }

    void set(UUID world, Message.IdlePose point) throws IOException {
        Message.IdlePose previous = point == null ? points.remove(world) : points.put(world, point);
        Path temporary = null;
        try {
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.set("version", 1);
            ConfigurationSection worlds = configuration.createSection("worlds");
            for (var saved : points.entrySet()) {
                ConfigurationSection entry = worlds.createSection(saved.getKey().toString());
                Message.IdlePose pose = saved.getValue();
                entry.set("x", pose.x());
                entry.set("y", pose.y());
                entry.set("z", pose.z());
                entry.set("yaw", pose.yaw());
                entry.set("pitch", pose.pitch());
            }
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "idle-points.", ".tmp");
            configuration.save(temporary.toFile());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            if (previous == null) points.remove(world);
            else points.put(world, previous);
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
            }
            throw exception;
        }
    }
}

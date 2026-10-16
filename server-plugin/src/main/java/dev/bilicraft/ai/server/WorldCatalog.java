package dev.bilicraft.ai.server;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit UUID allowlist; an existing empty catalog must never trigger legacy re-import. */
final class WorldCatalog {
    private final Path file;
    private final Map<UUID, Profile> worlds = new LinkedHashMap<>();
    private boolean initialized;

    WorldCatalog(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) {
            return;
        }
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file.toFile());
            if (!Integer.valueOf(1).equals(configuration.get("version"))
                    || !configuration.getKeys(false).equals(Set.of("version", "worlds"))) {
                throw new IllegalArgumentException("worlds.yml 必须使用 version: 1 和 worlds 映射");
            }
            ConfigurationSection section = configuration.getConfigurationSection("worlds");
            if (section == null) {
                throw new IllegalArgumentException("worlds 必须是 UUID 到配置的映射，空名单使用 worlds: {}");
            }
            for (String key : section.getKeys(false)) {
                UUID id = UUID.fromString(key);
                if (!id.toString().equalsIgnoreCase(key)) {
                    throw new IllegalArgumentException("世界 UUID 必须为完整规范格式：" + key);
                }
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null || !entry.getKeys(false).equals(Set.of("name", "scenes", "exclude-regions"))
                        || !(entry.get("name") instanceof String name)) {
                    throw new IllegalArgumentException("世界 " + key + " 必须且仅包含 name/scenes/exclude-regions");
                }
                Profile profile = new Profile(id, name, SceneConfiguration.parseEntries(entry.get("scenes")),
                        SceneConfiguration.parseExclusions(entry.get("exclude-regions")));
                if (worlds.values().stream().anyMatch(existing -> existing.lastKnownName.equals(name))) {
                    throw new IllegalArgumentException("世界 name 重复：" + name);
                }
                if (worlds.putIfAbsent(id, profile) != null) {
                    throw new IllegalArgumentException("世界 UUID 重复：" + key);
                }
            }
        } catch (InvalidConfigurationException | IllegalArgumentException exception) {
            throw new IOException("世界允许名单读取失败：" + file, exception);
        }
        initialized = true;
    }

    boolean initialized() {
        return initialized;
    }

    Collection<Profile> profiles() {
        return List.copyOf(worlds.values());
    }

    Profile get(UUID id) {
        return worlds.get(id);
    }

    void put(Profile profile) throws IOException {
        if (worlds.values().stream().anyMatch(existing -> !existing.id.equals(profile.id)
                && existing.lastKnownName.equals(profile.lastKnownName))) {
            throw new IOException("另一个 UUID 已使用该世界名称：" + profile.lastKnownName);
        }
        Profile previous = worlds.put(profile.id, profile);
        try {
            save();
        } catch (IOException exception) {
            if (previous == null) {
                worlds.remove(profile.id);
            } else {
                worlds.put(profile.id, previous);
            }
            throw exception;
        }
    }

    void remove(UUID id) throws IOException {
        Profile previous = worlds.remove(id);
        try {
            save();
        } catch (IOException exception) {
            if (previous != null) {
                worlds.put(id, previous);
            }
            throw exception;
        }
    }

    void save() throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 1);
        ConfigurationSection section = configuration.createSection("worlds");
        for (Profile profile : worlds.values()) {
            ConfigurationSection entry = section.createSection(profile.id.toString());
            entry.set("name", profile.lastKnownName);
            entry.set("scenes", profile.scenes.stream().map(scene -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("id", scene.id());
                values.put("name", scene.name());
                values.put("kind", scene.kind().name().toLowerCase(java.util.Locale.ROOT));
                values.put("x", scene.x());
                values.put("y", scene.y());
                values.put("z", scene.z());
                values.put("radius", scene.radius());
                return values;
            }).toList());
            entry.set("exclude-regions", profile.exclusions.stream().map(region -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("min-x", region.minX());
                values.put("max-x", region.maxX());
                values.put("min-z", region.minZ());
                values.put("max-z", region.maxZ());
                return values;
            }).toList());
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "worlds.", ".tmp");
        try {
            configuration.save(temporary.toFile());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            initialized = true;
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    record Profile(UUID id, String lastKnownName, List<SceneConfiguration.ConfiguredScene> scenes,
                   List<SceneConfiguration.Bounds> exclusions) {
        Profile {
            if (lastKnownName.isBlank() || !lastKnownName.equals(lastKnownName.strip())
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(lastKnownName)
                    || lastKnownName.getBytes(StandardCharsets.UTF_8).length > 256
                    || lastKnownName.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("世界 name 必须是实际名称，不能含首尾空白或控制字符，最多 256 UTF-8 字节");
            }
            scenes = List.copyOf(scenes);
            exclusions = List.copyOf(exclusions);
        }
    }
}

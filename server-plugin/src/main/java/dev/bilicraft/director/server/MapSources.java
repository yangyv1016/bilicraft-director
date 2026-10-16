package dev.bilicraft.director.server;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Administrator-provided external endpoints only; no downloaded map data or players are stored here. */
final class MapSources {
    private final Path file;
    private final Map<UUID, Source> sources = new LinkedHashMap<>();

    MapSources(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) return;
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(file.toFile());
            if (!Integer.valueOf(1).equals(configuration.get("version"))
                    || !configuration.getKeys(false).equals(Set.of("version", "worlds"))) {
                throw new IllegalArgumentException("map-sources.yml 须包含 version: 1 和 worlds 映射");
            }
            ConfigurationSection worlds = configuration.getConfigurationSection("worlds");
            if (worlds == null) throw new IllegalArgumentException("worlds 必须为 UUID 映射，空映射使用 {}");
            for (String key : worlds.getKeys(false)) {
                UUID id = UUID.fromString(key);
                ConfigurationSection entry = worlds.getConfigurationSection(key);
                if (!id.toString().equalsIgnoreCase(key) || entry == null
                        || !entry.getKeys(false).equals(Set.of("name", "base-url", "dynmap-world"))
                        || !(entry.get("name") instanceof String name)
                        || !(entry.get("base-url") instanceof String baseUrl)
                        || !(entry.get("dynmap-world") instanceof String dynmapWorld)) {
                    throw new IllegalArgumentException("地图源必须以规范 UUID 为键，仅包含 name/base-url/dynmap-world");
                }
                if (sources.putIfAbsent(id, new Source(id, name, baseUrl, dynmapWorld)) != null) {
                    throw new IllegalArgumentException("重复地图源 UUID");
                }
            }
        } catch (InvalidConfigurationException | IllegalArgumentException exception) {
            throw new IOException("地图源配置读取失败：" + file, exception);
        }
    }

    Source get(UUID world) {
        return sources.get(world);
    }

    List<Source> sources() {
        return List.copyOf(sources.values());
    }

    void put(Source source) throws IOException {
        Source previous = sources.put(source.worldId(), source);
        try {
            save();
        } catch (IOException exception) {
            if (previous == null) sources.remove(source.worldId());
            else sources.put(source.worldId(), previous);
            throw exception;
        }
    }

    void remove(UUID world) throws IOException {
        Source previous = sources.remove(world);
        try {
            save();
        } catch (IOException exception) {
            if (previous != null) sources.put(world, previous);
            throw exception;
        }
    }

    private void save() throws IOException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("version", 1);
        ConfigurationSection worlds = configuration.createSection("worlds");
        for (Source source : sources.values()) {
            ConfigurationSection entry = worlds.createSection(source.worldId().toString());
            entry.set("name", source.lastKnownName());
            entry.set("base-url", source.baseUrl());
            entry.set("dynmap-world", source.dynmapWorld());
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "map-sources.", ".tmp");
        try {
            configuration.save(temporary.toFile());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    record Source(UUID worldId, String lastKnownName, String baseUrl, String dynmapWorld) {
        Source {
            URI base = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("base URL 必须是无账号密码、查询或片段的 HTTP(S) 部署地址");
            }
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String[] values = {lastKnownName, baseUrl, dynmapWorld};
            for (int index = 0; index < values.length; index++) {
                String text = values[index];
                if (text.isBlank() || !text.equals(text.strip()) || text.codePoints().anyMatch(Character::isISOControl)
                        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                        || text.getBytes(StandardCharsets.UTF_8).length > (index == 1 ? 1_024 : 256)) {
                    throw new IllegalArgumentException("地图源名称/世界最多256 UTF-8字节，URL最多1024字节，禁止控制字符或首尾空白");
                }
            }
        }
    }
}

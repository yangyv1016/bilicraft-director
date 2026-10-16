package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Message.SceneView;
import dev.bilicraft.director.protocol.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** The live scene library is independent of the smaller, protocol-bounded disk cache. */
final class SceneLibrary {
    private SceneLibrary() {
    }

    static List<SceneView> merge(String world, List<SceneView> serverScenes, List<SceneView> mapScenes) {
        LinkedHashMap<String, SceneView> scenes = new LinkedHashMap<>();
        for (SceneView scene : serverScenes) {
            if (scene.world().equals(world)) scenes.putIfAbsent(scene.id(), scene);
        }
        for (SceneView scene : mapScenes) {
            if (scene.world().equals(world)) scenes.putIfAbsent(scene.id(), scene);
        }
        return List.copyOf(scenes.values());
    }

    static CacheSelection selectCache(String world, List<SceneView> currentScenes,
                                      List<SceneView> previousCache) throws IOException {
        ArrayList<SceneView> candidates = new ArrayList<>(currentScenes);
        previousCache.stream().filter(scene -> !scene.world().equals(world)).forEach(candidates::add);
        ArrayList<SceneView> selected = new ArrayList<>();
        int emptySize = Codec.encode(new Message.Snapshot(List.of(), List.of())).length;
        int remaining = Protocol.MAX_PACKET_BYTES - emptySize;
        boolean limited = false;
        for (SceneView scene : candidates) {
            if (selected.size() == Protocol.MAX_SCENES) {
                limited = true;
                break;
            }
            int bytes = Codec.encode(new Message.Snapshot(List.of(), List.of(scene))).length - emptySize;
            if (bytes <= remaining) {
                selected.add(scene);
                remaining -= bytes;
            } else {
                limited = true;
            }
        }
        return new CacheSelection(List.copyOf(selected), limited);
    }

    record CacheSelection(List<SceneView> scenes, boolean limited) {
    }
}

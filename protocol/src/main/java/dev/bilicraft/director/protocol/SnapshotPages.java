package dev.bilicraft.director.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Bounded, lossless transport pages; players and scenes occupy separate pages. */
public final class SnapshotPages {
    private SnapshotPages() {
    }

    public static List<Message.SnapshotPage> create(long requestId, List<Message.PlayerSummary> players,
                                                    List<Message.SceneView> scenes) {
        if (requestId <= 0 || players.size() > Protocol.MAX_SNAPSHOT_PLAYERS
                || scenes.size() > Protocol.MAX_SNAPSHOT_SCENES) {
            throw new IllegalArgumentException("Invalid snapshot request or total size");
        }
        HashSet<UUID> playerIds = new HashSet<>();
        for (Message.PlayerSummary player : players) {
            if (player.id() == null || !playerIds.add(player.id())) {
                throw new IllegalArgumentException("Missing or duplicate snapshot player ID");
            }
        }
        HashSet<List<String>> sceneKeys = new HashSet<>();
        for (Message.SceneView scene : scenes) {
            if (scene.world() == null || scene.id() == null || !sceneKeys.add(List.of(scene.world(), scene.id()))) {
                throw new IllegalArgumentException("Missing or duplicate snapshot scene ID");
            }
        }
        int playerPages = (players.size() + Protocol.MAX_SNAPSHOT_PAGE_PLAYERS - 1) / Protocol.MAX_SNAPSHOT_PAGE_PLAYERS;
        int scenePages = (scenes.size() + Protocol.MAX_SNAPSHOT_PAGE_SCENES - 1) / Protocol.MAX_SNAPSHOT_PAGE_SCENES;
        int count = Math.max(1, playerPages + scenePages);
        if (count > Protocol.MAX_SNAPSHOT_PAGES) {
            throw new IllegalArgumentException("Snapshot exceeds maximum page count");
        }
        ArrayList<Message.SnapshotPage> pages = new ArrayList<>(count);
        for (int offset = 0; offset < players.size(); offset += Protocol.MAX_SNAPSHOT_PAGE_PLAYERS) {
            pages.add(new Message.SnapshotPage(requestId, pages.size(), count,
                    players.subList(offset, Math.min(players.size(), offset + Protocol.MAX_SNAPSHOT_PAGE_PLAYERS)), List.of()));
        }
        for (int offset = 0; offset < scenes.size(); offset += Protocol.MAX_SNAPSHOT_PAGE_SCENES) {
            pages.add(new Message.SnapshotPage(requestId, pages.size(), count, List.of(),
                    scenes.subList(offset, Math.min(scenes.size(), offset + Protocol.MAX_SNAPSHOT_PAGE_SCENES))));
        }
        if (pages.isEmpty()) {
            pages.add(new Message.SnapshotPage(requestId, 0, 1, List.of(), List.of()));
        }
        return List.copyOf(pages);
    }
}

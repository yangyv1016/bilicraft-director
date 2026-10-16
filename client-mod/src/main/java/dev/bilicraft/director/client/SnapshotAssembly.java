package dev.bilicraft.director.client;

import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.Protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** One requested roster is published only after every page has arrived. */
final class SnapshotAssembly {
    record Result(List<Message.PlayerSummary> players, List<Message.SceneView> scenes) { }

    private final long requestId;
    private final String world;
    private final long generation;
    private Message.SnapshotPage[] pages;
    private int received;

    SnapshotAssembly(long requestId, String world, long generation) {
        this.requestId = requestId;
        this.world = world;
        this.generation = generation;
    }

    Result accept(Message.SnapshotPage page) throws IOException {
        if (page.requestId() != requestId) return null;
        if (page.pageCount() < 1 || page.pageCount() > Protocol.MAX_SNAPSHOT_PAGES
                || page.pageIndex() < 0 || page.pageIndex() >= page.pageCount()) {
            throw new IOException("Invalid snapshot page bounds");
        }
        if (pages == null) pages = new Message.SnapshotPage[page.pageCount()];
        if (pages.length != page.pageCount()) throw new IOException("Snapshot page count changed");
        if (pages[page.pageIndex()] != null) return null;
        pages[page.pageIndex()] = page;
        if (++received != pages.length) return null;
        ArrayList<Message.PlayerSummary> players = new ArrayList<>();
        ArrayList<Message.SceneView> scenes = new ArrayList<>();
        HashSet<UUID> playerIds = new HashSet<>();
        HashSet<String> sceneIds = new HashSet<>();
        for (Message.SnapshotPage part : pages) {
            for (Message.PlayerSummary player : part.players()) {
                if (!playerIds.add(player.id())) throw new IOException("Duplicate player in snapshot");
                players.add(player);
            }
            for (Message.SceneView scene : part.scenes()) {
                if (!sceneIds.add(scene.world() + "\0" + scene.id())) throw new IOException("Duplicate scene in snapshot");
                scenes.add(scene);
            }
        }
        if (players.size() > Protocol.MAX_SNAPSHOT_PLAYERS || scenes.size() > Protocol.MAX_SNAPSHOT_SCENES) {
            throw new IOException("Snapshot exceeds collection limits");
        }
        return new Result(List.copyOf(players), List.copyOf(scenes));
    }

    boolean belongsTo(String currentWorld, long currentGeneration) {
        return world.equals(currentWorld) && generation == currentGeneration;
    }
}

package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Codec;
import dev.bilicraft.director.protocol.Message;
import dev.bilicraft.director.protocol.SnapshotPages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.bilicraft.director.protocol.Message.PlayerAvailability.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerRosterTest {
    @TempDir
    Path directory;
    private final World overworld = world("overworld");
    private final World nether = world("the_nether");
    private final TestPlayer camera = new TestPlayer(1, overworld, 0);
    private final ObservationSessions sessions = new ObservationSessions();
    private final Map<UUID, Long> activity = new HashMap<>();

    @Test
    void aCompleteRosterIncludesFarPlayersAndSurvivesProtocolPaging() throws Exception {
        PrivacyStore privacy = new PrivacyStore(directory.resolve("privacy.properties"));
        ArrayList<Player> online = new ArrayList<>();
        for (int index = 2; index <= 301; index++) {
            TestPlayer target = new TestPlayer(index, overworld, 10_000 + index);
            online.add(target.player);
            activity.put(target.id, 20_000L);
        }
        List<Message.PlayerSummary> roster = PlayerRoster.snapshot(camera.player, online, privacy, sessions,
                activity, location -> true, 20_000);
        assertEquals(300, roster.size());
        assertTrue(roster.stream().allMatch(player -> player.availability() == AVAILABLE));
        ArrayList<Message.PlayerSummary> received = new ArrayList<>();
        List<Message.SnapshotPage> pages = SnapshotPages.create(7, roster, List.of());
        assertTrue(pages.size() > 1);
        for (Message.SnapshotPage page : pages) {
            Message.SnapshotPage decoded = (Message.SnapshotPage) Codec.decode(Codec.encode(page));
            received.addAll(decoded.players());
        }
        assertEquals(roster, received);
    }

    @Test
    void hiddenPlayersAndTheirNamesNeverEnterTheRosterOrEncodedPages() throws Exception {
        PrivacyStore privacy = new PrivacyStore(directory.resolve("privacy.properties"));
        TestPlayer visible = new TestPlayer(2, overworld, 500);
        TestPlayer invisible = new TestPlayer(3, overworld, 500);
        invisible.invisible = true;
        TestPlayer vanished = new TestPlayer(4, overworld, 500);
        vanished.vanished = true;
        TestPlayer hidden = new TestPlayer(5, overworld, 500);
        camera.hidden.add(hidden.id);
        TestPlayer offline = new TestPlayer(6, overworld, 500);
        offline.online = false;
        activity.put(visible.id, 20_000L);
        List<Message.PlayerSummary> roster = PlayerRoster.snapshot(camera.player,
                List.of(camera.player, visible.player, invisible.player, vanished.player, hidden.player, offline.player),
                privacy, sessions, activity, location -> true, 20_000);
        assertEquals(List.of(visible.id), roster.stream().map(Message.PlayerSummary::id).toList());
        byte[] encoded = Codec.encode(SnapshotPages.create(8, roster, List.of()).getFirst());
        String text = new String(encoded, StandardCharsets.UTF_8);
        for (TestPlayer concealed : List.of(invisible, vanished, hidden, offline)) {
            assertFalse(text.contains(concealed.name));
        }
    }

    @Test
    void visibleOnlinePlayersKeepWorldAndAvailabilityWithoutBeingDropped() throws Exception {
        PrivacyStore privacy = new PrivacyStore(directory.resolve("privacy.properties"));
        TestPlayer available = new TestPlayer(2, overworld, 20_000);
        TestPlayer otherWorld = new TestPlayer(3, nether, 20_000);
        TestPlayer spectator = new TestPlayer(4, overworld, 20_000);
        spectator.mode = GameMode.SPECTATOR;
        TestPlayer idle = new TestPlayer(5, overworld, 20_000);
        TestPlayer cooldown = new TestPlayer(6, overworld, 20_000);
        TestPlayer blacklist = new TestPlayer(7, overworld, 20_000);
        TestPlayer busy = new TestPlayer(8, overworld, 20_000);
        TestPlayer excluded = new TestPlayer(9, overworld, -1);
        List<TestPlayer> targets = List.of(available, otherWorld, spectator, idle, cooldown, blacklist, busy, excluded);
        targets.forEach(player -> activity.put(player.id, 20_000L));
        activity.put(idle.id, 10_000L);
        privacy.setCooldown(cooldown.id, 30_000);
        privacy.setBlacklist(blacklist.id, true);
        sessions.begin(UUID.randomUUID(), busy.id, 19_000, 180_000);
        List<Message.PlayerSummary> roster = PlayerRoster.snapshot(camera.player,
                targets.stream().map(player -> player.player).toList(), privacy, sessions, activity,
                location -> location.getX() >= 0, 20_000);
        assertEquals(targets.size(), roster.size());
        assertEquals(List.of(AVAILABLE, OTHER_WORLD, SPECTATOR, IDLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE),
                roster.stream().map(Message.PlayerSummary::availability).toList());
        assertEquals("minecraft:the_nether", roster.get(1).world());
    }

    @Test
    void inputIterationOrderCannotChangeTheStableRosterOrder() throws Exception {
        PrivacyStore privacy = new PrivacyStore(directory.resolve("privacy.properties"));
        ArrayList<Player> online = new ArrayList<>();
        for (int id : new int[]{7, 2, 9, 3}) online.add(new TestPlayer(id, overworld, id).player);
        List<Message.PlayerSummary> first = PlayerRoster.snapshot(camera.player, online, privacy, sessions,
                activity, location -> true, 20_000);
        Collections.reverse(online);
        assertEquals(first, PlayerRoster.snapshot(camera.player, online, privacy, sessions,
                activity, location -> true, 20_000));
    }

    @Test
    void observationEligibilityAllowsAnUnloadedFarSameWorldTargetAndStillEnforcesPrivacyGeometry() {
        TestPlayer far = new TestPlayer(2, overworld, 20_000);
        assertTrue(PlayerRoster.eligible(camera.player, far.player, location -> true));
        assertFalse(PlayerRoster.eligible(camera.player, far.player, location -> false));
        assertFalse(PlayerRoster.eligible(camera.player, new TestPlayer(3, nether, 20_000).player, location -> true));
        far.mode = GameMode.SPECTATOR;
        assertFalse(PlayerRoster.eligible(camera.player, far.player, location -> true));
        far.mode = GameMode.SURVIVAL;
        camera.hidden.add(far.id);
        assertFalse(PlayerRoster.eligible(camera.player, far.player, location -> true));
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getKey" -> NamespacedKey.minecraft(name);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new AssertionError("Unexpected World call: " + method.getName());
                });
    }

    private static final class TestPlayer {
        final UUID id;
        final String name;
        final Set<UUID> hidden = new HashSet<>();
        final Player player;
        boolean online = true;
        boolean invisible;
        boolean vanished;
        GameMode mode = GameMode.SURVIVAL;

        TestPlayer(int number, World world, double x) {
            id = new UUID(0, number);
            name = "Player" + number;
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "getWorld" -> world;
                        case "getLocation" -> new Location(world, x, 70, 0);
                        case "isOnline" -> online;
                        case "isInvisible" -> invisible;
                        case "getGameMode" -> mode;
                        case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
                        case "getMetadata" -> vanished ? List.of(Proxy.newProxyInstance(MetadataValue.class.getClassLoader(),
                                new Class<?>[]{MetadataValue.class}, (metadata, call, values) -> {
                                    if (call.getName().equals("asBoolean")) return true;
                                    throw new AssertionError("Unexpected metadata call: " + call.getName());
                                })) : List.of();
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> name;
                        default -> throw new AssertionError("Unexpected Player call: " + method.getName());
                    });
        }
    }
}

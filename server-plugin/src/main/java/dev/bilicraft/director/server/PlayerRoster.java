package dev.bilicraft.director.server;

import dev.bilicraft.director.protocol.Message;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Captures a complete visible roster on the server thread without distributing player positions. */
final class PlayerRoster {
    static boolean visibleTo(Player camera, Player target) {
        return !camera.getUniqueId().equals(target.getUniqueId()) && target.isOnline() && camera.canSee(target)
                && !target.isInvisible() && target.getMetadata("vanished").stream().noneMatch(value -> value.asBoolean());
    }

    static boolean eligible(Player camera, Player target, Predicate<Location> insideViewingArea) {
        return visibleTo(camera, target) && camera.getWorld().equals(target.getWorld())
                && target.getGameMode() != GameMode.SPECTATOR && insideViewingArea.test(target.getLocation());
    }

    static List<Message.PlayerSummary> snapshot(Player camera, Collection<? extends Player> players, PrivacyStore privacy,
                                                 ObservationSessions sessions, Map<UUID, Long> activity,
                                                 Predicate<Location> insideViewingArea, long now) {
        return players.stream().filter(target -> visibleTo(camera, target))
                .sorted(Comparator.comparing(Player::getUniqueId))
                .map(target -> {
                    Message.PlayerAvailability availability;
                    if (!camera.getWorld().equals(target.getWorld())) {
                        availability = Message.PlayerAvailability.OTHER_WORLD;
                    } else if (target.getGameMode() == GameMode.SPECTATOR) {
                        availability = Message.PlayerAvailability.SPECTATOR;
                    } else if (!privacy.available(target.getUniqueId(), now) || sessions.forTarget(target.getUniqueId()) != null
                            || !insideViewingArea.test(target.getLocation())) {
                        availability = Message.PlayerAvailability.UNAVAILABLE;
                    } else if (now - activity.getOrDefault(target.getUniqueId(), 0L) >= 10_000) {
                        availability = Message.PlayerAvailability.IDLE;
                    } else {
                        availability = Message.PlayerAvailability.AVAILABLE;
                    }
                    return new Message.PlayerSummary(target.getUniqueId(), target.getName(),
                            target.getWorld().getKey().toString(), availability);
                }).toList();
    }

    private PlayerRoster() {
    }
}

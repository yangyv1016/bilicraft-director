package dev.bilicraft.ai.server;

import dev.bilicraft.ai.protocol.Protocol;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Main-thread session ownership, notice deadlines and unforgeable action links. */
final class ObservationSessions {
    static final long NOTICE_MILLIS = 15_000;
    static final long HEARTBEAT_TIMEOUT_MILLIS = 10_000;
    private final Map<UUID, Session> byCamera = new HashMap<>();
    private final Map<UUID, Session> byTarget = new HashMap<>();

    Session begin(UUID camera, UUID target, long now, long followDurationMillis) {
        if (byCamera.containsKey(camera) || byTarget.containsKey(target)) {
            throw new IllegalStateException("Camera or target already has an observation session");
        }
        Session session = new Session(camera, target, UUID.randomUUID(), now,
                now + NOTICE_MILLIS, followDurationMillis);
        byCamera.put(camera, session);
        byTarget.put(target, session);
        return session;
    }

    Session forCamera(UUID camera) {
        return byCamera.get(camera);
    }

    Session forTarget(UUID target) {
        return byTarget.get(target);
    }

    Collection<Session> all() {
        return List.copyOf(byCamera.values());
    }

    /** The actor comes from the command sender, never from a command argument. */
    Session forAction(UUID actor, UUID token, long now) {
        Session session = byTarget.get(actor);
        if (session == null || !session.token.equals(token)
                || now - session.heartbeatAt >= HEARTBEAT_TIMEOUT_MILLIS
                || session.following && now >= session.followDeadline) {
            return null;
        }
        return session;
    }

    /** Authorization is checked at the transition, including the exact 15-second boundary. */
    boolean startFollowing(UUID camera, long now, BooleanSupplier authorized) {
        Session session = byCamera.get(camera);
        if (session == null || session.following || now < session.noticeDeadline || !authorized.getAsBoolean()) {
            return false;
        }
        session.following = true;
        session.followingStartedAt = now;
        session.followDeadline = now + session.followDurationMillis;
        return true;
    }

    DurationChange changeDuration(UUID camera, UUID target, int requestedSeconds, long now,
                                  BooleanSupplier authorized, DurationReservation reservation) throws IOException {
        Session session = byCamera.get(camera);
        if (session == null || !session.target.equals(target)) {
            return new DurationChange(ChangeStatus.NO_SESSION, null);
        }
        if (!authorized.getAsBoolean()) {
            return new DurationChange(ChangeStatus.NOT_AUTHORIZED, session);
        }
        long requestedMillis = requestedSeconds * 1_000L;
        if (requestedSeconds < Protocol.MIN_FOLLOW_SECONDS || requestedSeconds > Protocol.MAX_FOLLOW_SECONDS
                || Math.abs(requestedMillis - session.followDurationMillis) != 10_000) {
            return new DurationChange(ChangeStatus.INVALID_DURATION, session);
        }
        if (requestedMillis > session.followDurationMillis) {
            long projectedEnd = session.following ? session.followingStartedAt + requestedMillis
                    : Math.max(now, session.noticeDeadline) + requestedMillis;
            reservation.reserveUntil(projectedEnd);
            if (!authorized.getAsBoolean()) {
                return new DurationChange(ChangeStatus.NOT_AUTHORIZED, session);
            }
        }
        session.followDurationMillis = requestedMillis;
        if (session.following) {
            session.followDeadline = session.followingStartedAt + requestedMillis;
        }
        return new DurationChange(ChangeStatus.APPLIED, session);
    }

    enum ChangeStatus { APPLIED, NO_SESSION, NOT_AUTHORIZED, INVALID_DURATION }

    record DurationChange(ChangeStatus status, Session session) {
    }

    @FunctionalInterface
    interface DurationReservation {
        void reserveUntil(long followEndEpochMillis) throws IOException;
    }

    void end(Session session) {
        // A delayed callback holding an old session cannot remove a newer one.
        byCamera.remove(session.camera, session);
        byTarget.remove(session.target, session);
    }

    static final class Session {
        final UUID camera;
        final UUID target;
        final UUID token;
        final long createdAt;
        final long noticeDeadline;
        long followDurationMillis;
        long heartbeatAt;
        long followingStartedAt;
        long followDeadline;
        boolean following;

        Session(UUID camera, UUID target, UUID token, long now, long noticeDeadline, long followDurationMillis) {
            this.camera = camera;
            this.target = target;
            this.token = token;
            this.createdAt = now;
            this.noticeDeadline = noticeDeadline;
            this.followDurationMillis = followDurationMillis;
            heartbeatAt = now;
        }
    }
}

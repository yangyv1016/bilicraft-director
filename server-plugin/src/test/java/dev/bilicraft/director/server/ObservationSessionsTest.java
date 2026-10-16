package dev.bilicraft.director.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ObservationSessionsTest {
    @TempDir
    Path directory;
    private final ObservationSessions sessions = new ObservationSessions();
    private final UUID camera = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @Test
    void noticeNeverGrantsEarlyAndRechecksAuthorizationAtExactDeadline() {
        ObservationSessions.Session session = sessions.begin(camera, target, 1_000, 180_000);
        assertFalse(sessions.startFollowing(camera, 15_999, () -> true));
        assertFalse(session.following);
        assertFalse(sessions.startFollowing(camera, 16_000, () -> false));
        assertFalse(session.following);
        assertTrue(sessions.startFollowing(camera, 16_000, () -> true));
        assertEquals(196_000, session.followDeadline);
        assertFalse(sessions.startFollowing(camera, 16_001, () -> true));
    }

    @Test
    void anotherPlayerCannotUseLeakedActionToken() {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        assertNull(sessions.forAction(camera, session.token, 0));
        assertNull(sessions.forAction(UUID.randomUUID(), session.token, 0));
        assertNull(sessions.forAction(target, UUID.randomUUID(), 0));
        assertSame(session, sessions.forAction(target, session.token, 0));
    }

    @Test
    void repeatedAndExpiredLinksCannotAffectNextObservation() {
        ObservationSessions.Session old = sessions.begin(camera, target, 0, 180_000);
        sessions.end(old);
        assertNull(sessions.forAction(target, old.token, 0));
        ObservationSessions.Session next = sessions.begin(camera, target, 1_000_000, 180_000);
        assertNotEquals(old.token, next.token);
        assertNull(sessions.forAction(target, old.token, 1_000_000));
        sessions.end(old);
        assertSame(next, sessions.forCamera(camera));
        assertSame(next, sessions.forTarget(target));
    }

    @Test
    void rejectionAtNoticeBoundaryPreventsDelayedActivation() {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        session.heartbeatAt = 14_999;
        assertSame(session, sessions.forAction(target, session.token, 15_000));
        sessions.end(session);
        AtomicBoolean queried = new AtomicBoolean();
        assertFalse(sessions.startFollowing(camera, 15_000, () -> {
            queried.set(true);
            return true;
        }));
        assertFalse(queried.get());
        assertNull(sessions.forCamera(camera));
    }

    @Test
    void oneTargetCannotReceiveConcurrentNoticesFromTwoCameras() {
        sessions.begin(camera, target, 0, 180_000);
        assertThrows(IllegalStateException.class,
                () -> sessions.begin(UUID.randomUUID(), target, 0, 180_000));
        assertThrows(IllegalStateException.class,
                () -> sessions.begin(camera, UUID.randomUUID(), 0, 180_000));
        assertEquals(1, sessions.all().size());
    }

    @Test
    void expiredFollowAndHeartbeatInvalidateLinksBeforeNextTick() {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        assertNull(sessions.forAction(target, session.token, 10_000));
        session.heartbeatAt = 15_000;
        assertTrue(sessions.startFollowing(camera, 15_000, () -> true));
        session.heartbeatAt = 194_999;
        assertSame(session, sessions.forAction(target, session.token, 194_999));
        assertNull(sessions.forAction(target, session.token, 195_000));
    }

    @Test
    void noticeAdjustmentNeverRestartsOrSkipsTheFifteenSecondWindow() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 1_000, 180_000);
        AtomicLong reservedEnd = new AtomicLong();
        ObservationSessions.DurationChange change = sessions.changeDuration(camera, target, 190, 2_000,
                () -> true, reservedEnd::set);
        assertEquals(ObservationSessions.ChangeStatus.APPLIED, change.status());
        assertSame(session, change.session());
        assertEquals(16_000, session.noticeDeadline);
        assertEquals(190_000, session.followDurationMillis);
        assertEquals(206_000, reservedEnd.get());
        assertFalse(session.following);
        assertFalse(sessions.startFollowing(camera, 15_999, () -> true));
        assertTrue(sessions.startFollowing(camera, 16_000, () -> true));
        assertEquals(206_000, session.followDeadline);
    }

    @Test
    void followingAdjustmentUsesOriginalStartAndDoesNotResetTheIdleClock() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 1_000, 240_000);
        assertTrue(sessions.startFollowing(camera, 20_000, () -> true));
        AtomicLong reservedEnd = new AtomicLong();
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 250, 50_000, () -> true, reservedEnd::set).status());
        assertEquals(20_000, session.followingStartedAt);
        assertEquals(270_000, session.followDeadline);
        assertEquals(270_000, reservedEnd.get());
        assertEquals(16_000, session.noticeDeadline);
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 240, 60_000, () -> true,
                        ignored -> fail("A shorter duration must not reduce the restart reservation")).status());
        assertEquals(260_000, session.followDeadline);
        assertEquals(20_000, session.followingStartedAt);
    }

    @Test
    void currentDurationCanShrinkPastNowWithoutExtendingOrReanchoringItsDeadline() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 240_000);
        assertTrue(sessions.startFollowing(camera, 15_000, () -> true));
        assertTrue(session.followDeadline > 250_000);
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 230, 250_000, () -> true,
                        ignored -> fail("A decrease needs no larger reservation")).status());
        assertEquals(245_000, session.followDeadline);
        assertEquals(230_000, session.followDurationMillis);
        assertEquals(15_000, session.followingStartedAt);
        assertTrue(session.followDeadline <= 250_000);
    }

    @Test
    void onlyExactTenSecondChangesInsideThreeToEightMinutesAreAccepted() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        for (int seconds : new int[]{Integer.MIN_VALUE, 0, 170, 179, 180, 181, 200, 480, 481, Integer.MAX_VALUE}) {
            assertEquals(ObservationSessions.ChangeStatus.INVALID_DURATION,
                    sessions.changeDuration(camera, target, seconds, 1_000, () -> true,
                            ignored -> fail("Invalid changes cannot persist or apply")).status());
            assertEquals(180_000, session.followDurationMillis);
        }
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 190, 1_000, () -> true, ignored -> {}).status());
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 180, 1_000, () -> true, ignored -> {}).status());
        sessions.end(session);
        session = sessions.begin(camera, target, 0, 480_000);
        assertEquals(ObservationSessions.ChangeStatus.INVALID_DURATION,
                sessions.changeDuration(camera, target, 490, 1_000, () -> true,
                        ignored -> fail("An over-limit change cannot persist")).status());
        assertEquals(480_000, session.followDurationMillis);
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 470, 1_000, () -> true, ignored -> {}).status());
    }

    @Test
    void cameraAndTargetOwnershipAreCheckedBeforeAnyAuthorizationOrPersistenceCallback() throws IOException {
        ObservationSessions.Session owned = sessions.begin(camera, target, 0, 180_000);
        UUID otherCamera = UUID.randomUUID();
        UUID otherTarget = UUID.randomUUID();
        ObservationSessions.Session other = sessions.begin(otherCamera, otherTarget, 0, 240_000);
        for (UUID requester : new UUID[]{UUID.randomUUID(), otherCamera}) {
            ObservationSessions.DurationChange change = sessions.changeDuration(requester, target, 190, 1_000,
                    () -> fail("A foreign camera cannot authorize this target"),
                    ignored -> fail("A foreign camera cannot reserve this target"));
            assertEquals(ObservationSessions.ChangeStatus.NO_SESSION, change.status());
            assertNull(change.session());
        }
        assertEquals(ObservationSessions.ChangeStatus.NO_SESSION,
                sessions.changeDuration(camera, otherTarget, 190, 1_000,
                        () -> fail("A mismatched target must not reach authorization"), ignored -> fail()).status());
        assertEquals(180_000, owned.followDurationMillis);
        assertEquals(240_000, other.followDurationMillis);
        assertSame(owned, sessions.forCamera(camera));
        assertSame(other, sessions.forCamera(otherCamera));
    }

    @Test
    void privacyRevocationWinsOverInvalidDurationAndMakesNoReservation() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        PrivacyStore privacy = new PrivacyStore(directory.resolve("privacy.properties"));
        privacy.setBlacklist(target, true);
        ObservationSessions.DurationChange change = sessions.changeDuration(camera, target, Integer.MIN_VALUE, 1_000,
                () -> !privacy.blacklisted(target), ignored -> fail("A blacklisted target cannot be extended"));
        assertEquals(ObservationSessions.ChangeStatus.NOT_AUTHORIZED, change.status());
        assertSame(session, change.session());
        assertEquals(180_000, session.followDurationMillis);
    }

    @Test
    void authorizationIsRecheckedAfterTheDurableReservation() throws IOException {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicBoolean reserved = new AtomicBoolean();
        ObservationSessions.DurationChange change = sessions.changeDuration(camera, target, 190, 1_000,
                authorized::get, ignored -> {
                    reserved.set(true);
                    authorized.set(false);
                });
        assertTrue(reserved.get());
        assertEquals(ObservationSessions.ChangeStatus.NOT_AUTHORIZED, change.status());
        assertEquals(180_000, session.followDurationMillis);
        assertFalse(session.following);
    }

    @Test
    void failedReservationNeverAcknowledgesOrMutatesARequestedExtension() {
        ObservationSessions.Session session = sessions.begin(camera, target, 0, 180_000);
        assertTrue(sessions.startFollowing(camera, 15_000, () -> true));
        assertThrows(IOException.class, () -> sessions.changeDuration(camera, target, 190, 16_000, () -> true,
                ignored -> {
                    assertEquals(180_000, session.followDurationMillis);
                    assertEquals(195_000, session.followDeadline);
                    throw new IOException("fixture storage failure");
                }));
        assertEquals(180_000, session.followDurationMillis);
        assertEquals(195_000, session.followDeadline);
    }

    @Test
    void anExtensionPersistsTheWholeNewSessionAndCooldownBeforeApproval() throws IOException {
        long cooldown = 30 * 60_000L;
        Path file = directory.resolve("privacy.properties");
        PrivacyStore privacy = new PrivacyStore(file);
        ObservationSessions.Session session = sessions.begin(camera, target, 1_000, 180_000);
        privacy.setCooldown(target, session.noticeDeadline + session.followDurationMillis + cooldown);
        assertEquals(ObservationSessions.ChangeStatus.APPLIED,
                sessions.changeDuration(camera, target, 190, 2_000, () -> true,
                        followEnd -> privacy.setCooldown(target, Math.max(privacy.cooldownUntil(target), followEnd + cooldown))).status());
        PrivacyStore restarted = new PrivacyStore(file);
        assertFalse(restarted.available(target, 206_000 + cooldown - 1));
        assertTrue(restarted.available(target, 206_000 + cooldown));
    }
}

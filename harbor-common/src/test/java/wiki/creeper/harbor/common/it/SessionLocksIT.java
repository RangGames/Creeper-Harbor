package wiki.creeper.harbor.common.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.redis.RedisConfig;
import wiki.creeper.harbor.common.session.ClaimResult;
import wiki.creeper.harbor.common.session.LockState;
import wiki.creeper.harbor.common.session.SessionLocks;

/** 실제 Redis 컨테이너로 세션 잠금 프로토콜을 검사한다. Docker가 없으면 건너뛴다. */
@Testcontainers(disabledWithoutDocker = true)
class SessionLocksIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    static Redis redis;
    static SessionLocks locks;

    @BeforeAll
    static void setUp() {
        redis =
                Redis.connect(
                        new RedisConfig(
                                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)));
        locks = new SessionLocks(redis);
    }

    @AfterAll
    static void tearDown() {
        redis.close();
    }

    @Test
    void firstJoinClaimsAndOtherServerIsBusy() {
        UUID player = UUID.randomUUID();
        assertEquals(ClaimResult.CLAIMED, locks.claim(player, "main-1"));
        assertEquals(ClaimResult.CLAIMED, locks.claim(player, "main-1"));
        assertEquals(ClaimResult.BUSY, locks.claim(player, "main-2"));
        assertTrue(locks.release(player, "main-1"));
        assertEquals(ClaimResult.CLAIMED, locks.claim(player, "main-2"));
    }

    @Test
    void handoffMovesOwnershipOnlyToTarget() {
        UUID player = UUID.randomUUID();
        locks.claim(player, "main-1");
        assertFalse(locks.beginHandoff(player, "main-2", "inst-1", "req-1"));
        assertTrue(locks.beginHandoff(player, "main-1", "inst-1", "req-1"));
        assertEquals(Optional.of(new LockState.Handoff("inst-1", "req-1")), locks.inspect(player));

        // 출발 서버의 퇴장 처리가 넘긴 잠금을 owned 해제로 지우면 안 된다
        assertFalse(locks.release(player, "main-1"));
        assertEquals(ClaimResult.BUSY, locks.claim(player, "inst-2"));
        assertEquals(ClaimResult.HANDOFF_CLAIMED, locks.claim(player, "inst-1"));
        assertEquals(Optional.of(new LockState.Owned("inst-1")), locks.inspect(player));
        // 도착 서버가 가져간 뒤에는 되돌릴 수 없다
        assertFalse(locks.abortHandoff(player, "main-1", "req-1"));
    }

    @Test
    void abortRestoresOwnershipToOrigin() {
        UUID player = UUID.randomUUID();
        locks.claim(player, "main-1");
        assertTrue(locks.beginHandoff(player, "main-1", "inst-1", "req-2"));
        assertFalse(locks.abortHandoff(player, "main-1", "req-other"));
        assertTrue(locks.abortHandoff(player, "main-1", "req-2"));
        assertEquals(Optional.of(new LockState.Owned("main-1")), locks.inspect(player));
    }

    @Test
    void disconnectDuringHandoffReleasesOnlyThatRequest() {
        UUID player = UUID.randomUUID();
        locks.claim(player, "main-1");
        locks.beginHandoff(player, "main-1", "inst-1", "req-3");
        assertFalse(locks.releaseHandoff(player, "req-other"));
        assertTrue(locks.releaseHandoff(player, "req-3"));
        assertTrue(locks.inspect(player).isEmpty());
    }

    @Test
    void awaitClaimWaitsForHandoff() throws Exception {
        UUID player = UUID.randomUUID();
        locks.claim(player, "main-1");
        CompletableFuture<ClaimResult> target =
                CompletableFuture.supplyAsync(
                        () -> locks.awaitClaim(player, "inst-1", SessionLocks.HANDOFF_WAIT));
        Thread.sleep(500);
        assertTrue(locks.beginHandoff(player, "main-1", "inst-1", "req-4"));
        assertEquals(ClaimResult.HANDOFF_CLAIMED, target.get(5, TimeUnit.SECONDS));
    }

    @Test
    void refreshExtendsOnlyOwnLocks() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        locks.claim(mine, "main-1");
        locks.claim(other, "main-2");
        assertEquals(1, locks.refresh(List.of(mine, other), "main-1"));
    }

    @Test
    void distributedLockIsExclusive() {
        DistributedLock lock = new DistributedLock(redis);
        var first = lock.tryAcquire("harbor:test:lock", Duration.ofSeconds(5));
        assertTrue(first.isPresent());
        assertTrue(lock.tryAcquire("harbor:test:lock", Duration.ofSeconds(5)).isEmpty());
        assertTrue(first.get().release());
        assertTrue(lock.tryAcquire("harbor:test:lock", Duration.ofSeconds(5)).isPresent());
    }
}

package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisFailedAttemptRepositoryScriptTest {

    @Test
    void failedAttemptAndLockoutMutationsAreAtomicAndTtlSafe() {
        final var attempt = RedisFailedAttemptRepository.RECORD_ATTEMPT_SCRIPT;
        final var lockout = RedisFailedAttemptRepository.RECORD_LOCKOUT_SCRIPT;

        assertTrue(attempt.contains("redis.call('SET', KEYS[1], ARGV[2])"));
        assertTrue(attempt.contains("redis.call('INCR'"));
        assertTrue(attempt.contains("if initialized then redis.call('EXPIRE'"));
        assertTrue(lockout.contains("redis.call('TIME')"));
        assertTrue(lockout.contains("redis.call('EXISTS', KEYS[1]) == 1"));
        assertTrue(lockout.contains("redis.call('HSET'"));
        assertTrue(lockout.contains("redis.call('INCR'"));
        assertTrue(lockout.contains("redis.call('SET', KEYS[2], ARGV[4])"));
        assertTrue(lockout.contains("redis.call('DEL', KEYS[3])"));
    }
}

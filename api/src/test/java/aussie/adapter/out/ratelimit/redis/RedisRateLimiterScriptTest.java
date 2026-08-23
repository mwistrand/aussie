package aussie.adapter.out.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedisRateLimiterScriptTest {

    @Test
    void decisionsUseRedisTimeAndClampElapsedTime() {
        final var consume = RedisRateLimiter.TOKEN_BUCKET_SCRIPT;
        final var status = RedisRateLimiter.STATUS_SCRIPT;

        assertTrue(consume.contains("redis.call('TIME')"));
        assertTrue(status.contains("redis.call('TIME')"));
        assertTrue(consume.contains("math.max(0, now_ms - last_refill_ms)"));
        assertTrue(consume.contains("math.ceil((1 - tokens) / refill_rate)"));
        assertFalse(consume.contains("tonumber(ARGV[4])"));
    }
}

package aussie.benchmark;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for revocation cache lookup performance.
 *
 * <p>RevocationCache is an ApplicationScoped CDI bean. Rather than mocking the
 * entire bean, these benchmarks directly exercise the underlying Caffeine cache
 * operations that RevocationCache delegates to, since the RevocationCache methods
 * are thin wrappers around Caffeine get/put calls with Instant comparison logic.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class RevocationCacheBenchmark {

    private record RevocationEntry(Instant expiresAt) {}

    private record UserRevocationEntry(Instant issuedBefore, Instant expiresAt) {}

    @State(Scope.Benchmark)
    public static class JtiHitState {
        Cache<String, RevocationEntry> cache;
        String revokedJti;

        @Setup
        public void setup() {
            cache = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            revokedJti = "jti-revoked-1";
            cache.put(revokedJti, new RevocationEntry(Instant.now().plusSeconds(3600)));
            // Populate with additional entries for realistic LRU behavior
            for (var i = 0; i < 1000; i++) {
                cache.put("jti-filler-" + i, new RevocationEntry(Instant.now().plusSeconds(3600)));
            }
        }
    }

    @State(Scope.Benchmark)
    public static class JtiMissState {
        Cache<String, RevocationEntry> cache;

        @Setup
        public void setup() {
            cache = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            for (var i = 0; i < 1000; i++) {
                cache.put("jti-filler-" + i, new RevocationEntry(Instant.now().plusSeconds(3600)));
            }
        }
    }

    @State(Scope.Benchmark)
    public static class JtiExpiredState {
        Cache<String, RevocationEntry> cache;
        String expiredJti;

        @Setup
        public void setup() {
            cache = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            expiredJti = "jti-expired-1";
            // Entry with an expiresAt in the past (application-level expiry check)
            cache.put(expiredJti, new RevocationEntry(Instant.now().minusSeconds(60)));
        }
    }

    @State(Scope.Benchmark)
    public static class UserHitState {
        Cache<String, UserRevocationEntry> cache;
        String revokedUser;
        Instant tokenIssuedAt;

        @Setup
        public void setup() {
            cache = Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            revokedUser = "user-revoked-1";
            tokenIssuedAt = Instant.now().minusSeconds(120);
            cache.put(
                    revokedUser,
                    new UserRevocationEntry(Instant.now(), Instant.now().plusSeconds(3600)));
        }
    }

    @State(Scope.Thread)
    public static class WriteState {
        Cache<String, RevocationEntry> cache;
        long counter;

        @Setup(Level.Iteration)
        public void setup() {
            cache = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();
            counter = 0L;
        }
    }

    @Benchmark
    public void isJtiRevoked_hit(JtiHitState state, Blackhole bh) {
        var entry = state.cache.getIfPresent(state.revokedJti);
        if (entry != null) {
            bh.consume(entry.expiresAt().isAfter(Instant.now()));
        }
    }

    @Benchmark
    public void isJtiRevoked_miss(JtiMissState state, Blackhole bh) {
        bh.consume(state.cache.getIfPresent("jti-nonexistent"));
    }

    @Benchmark
    public void isJtiRevoked_expired(JtiExpiredState state, Blackhole bh) {
        var entry = state.cache.getIfPresent(state.expiredJti);
        bh.consume(entry != null && entry.expiresAt().isAfter(Instant.now()));
    }

    @Benchmark
    public void isUserRevoked_hit(UserHitState state, Blackhole bh) {
        var entry = state.cache.getIfPresent(state.revokedUser);
        if (entry != null) {
            bh.consume(entry.expiresAt().isAfter(Instant.now()) && state.tokenIssuedAt.isBefore(entry.issuedBefore()));
        }
    }

    @Benchmark
    public void cacheJtiRevocation_write(WriteState state, Blackhole bh) {
        state.cache.put(
                "jti-write-" + state.counter++,
                new RevocationEntry(Instant.now().plusSeconds(3600)));
        bh.consume(state.counter);
    }
}

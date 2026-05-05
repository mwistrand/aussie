package aussie.benchmark;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.smallrye.mutiny.Uni;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import aussie.adapter.out.auth.OidcTokenValidator;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.out.JwksCache;

/**
 * Steady-state benchmark for {@link OidcTokenValidator#validate}. Captures the cost of the
 * cache-hit path after the per-{@code (issuer, audiences, kid)} {@code JwtConsumer} is warm,
 * which is the common production case for any authenticated request.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class TokenValidationBenchmark {

    @State(Scope.Benchmark)
    public static class ValidateState {

        @Param({"false", "true"})
        boolean withAudience;

        OidcTokenValidator validator;
        TokenProviderConfig config;
        String token;

        @Setup
        public void setup() throws Exception {
            final var keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            final KeyPair keyPair = keyGen.generateKeyPair();

            final var rsaJwk = new RsaJsonWebKey((RSAPublicKey) keyPair.getPublic());
            rsaJwk.setKeyId("bench-key-1");
            rsaJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);

            final JwksCache jwksCache = new StaticJwksCache(rsaJwk);
            validator = new OidcTokenValidator(jwksCache, new BenchResiliencyConfig());

            final var builder = TokenProviderConfig.builder(
                    "bench-provider", "https://issuer.example.com", URI.create("https://issuer.example.com/jwks"));
            if (withAudience) {
                builder.audiences(Set.of("aud-a", "aud-b"));
            }
            config = builder.build();

            final var claims = new JwtClaims();
            claims.setSubject("user-1");
            claims.setIssuer("https://issuer.example.com");
            claims.setExpirationTime(
                    NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
            claims.setIssuedAt(NumericDate.now());
            claims.setGeneratedJwtId();
            if (withAudience) {
                claims.setAudience("aud-a");
            }

            final var jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setKey(keyPair.getPrivate());
            jws.setKeyIdHeaderValue("bench-key-1");
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
            token = jws.getCompactSerialization();

            // Pre-warm the consumer cache so the first measured iteration is a hit, which is
            // the per-request steady-state cost we want to measure. Assert success to avoid
            // silently measuring the much-cheaper Invalid recovery path.
            final var warmup = validator.validate(token, config).await().indefinitely();
            if (!(warmup instanceof TokenValidationResult.Valid)) {
                throw new IllegalStateException("Benchmark warmup did not produce a valid token: " + warmup);
            }
        }
    }

    @Benchmark
    public void validate(ValidateState state, Blackhole bh) {
        bh.consume(state.validator.validate(state.token, state.config).await().indefinitely());
    }

    private static final class StaticJwksCache implements JwksCache {
        private final RsaJsonWebKey key;

        StaticJwksCache(RsaJsonWebKey key) {
            this.key = key;
        }

        @Override
        public Uni<JsonWebKeySet> getKeySet(URI jwksUri) {
            return Uni.createFrom().item(new JsonWebKeySet(key));
        }

        @Override
        public Uni<Optional<JsonWebKey>> getKey(URI jwksUri, String keyId) {
            return Uni.createFrom().item(Optional.of(key));
        }

        @Override
        public Uni<JsonWebKeySet> refresh(URI jwksUri) {
            return getKeySet(jwksUri);
        }

        @Override
        public void invalidate(URI jwksUri) {}
    }

    private static final class BenchResiliencyConfig implements ResiliencyConfig {
        private final JwksConfig jwksConfig = new BenchJwksConfig();

        @Override
        public HttpConfig http() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JwksConfig jwks() {
            return jwksConfig;
        }

        @Override
        public CassandraConfig cassandra() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RedisConfig redis() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class BenchJwksConfig implements ResiliencyConfig.JwksConfig {
        @Override
        public Duration fetchTimeout() {
            return Duration.ofSeconds(5);
        }

        @Override
        public int maxCacheEntries() {
            return 100;
        }

        @Override
        public Duration cacheTtl() {
            return Duration.ofHours(1);
        }

        @Override
        public int maxConnections() {
            return 10;
        }
    }
}

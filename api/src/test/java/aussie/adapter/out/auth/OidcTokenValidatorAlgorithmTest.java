package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;

import io.smallrye.mutiny.Uni;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.ResiliencyConfig;
import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.out.JwksCache;

@DisplayName("OidcTokenValidator algorithm/JTI/audience hardening")
@ExtendWith(MockitoExtension.class)
class OidcTokenValidatorAlgorithmTest {

    private static final String ISSUER = "https://auth.example.com";
    private static final String SUBJECT = "user-1";
    private static final String KEY_ID = "test-key-1";
    private static final String AUDIENCE = "aussie-gateway";
    private static final URI JWKS_URI = URI.create("https://auth.example.com/.well-known/jwks.json");

    private static KeyPair rsaKeyPair;
    private static RsaJsonWebKey rsaJwk;

    @Mock
    private JwksCache jwksCache;

    @Mock
    private ResiliencyConfig resiliencyConfig;

    @Mock
    private ResiliencyConfig.JwksConfig jwksConfig;

    private OidcTokenValidator validator;
    private TokenProviderConfig config;

    @BeforeAll
    static void setUpKeys() throws Exception {
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        rsaKeyPair = gen.generateKeyPair();
        rsaJwk = new RsaJsonWebKey((RSAPublicKey) rsaKeyPair.getPublic());
        rsaJwk.setKeyId(KEY_ID);
        rsaJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        rsaJwk.setUse("sig");
    }

    @BeforeEach
    void setUp() {
        when(resiliencyConfig.jwks()).thenReturn(jwksConfig);
        when(jwksConfig.cacheTtl()).thenReturn(Duration.ofHours(1));
        validator = new OidcTokenValidator(jwksCache, resiliencyConfig);
        config = TokenProviderConfig.builder("test-provider", ISSUER, JWKS_URI)
                .audiences(Set.of(AUDIENCE))
                .allowedAlgorithms(Set.of("RS256"))
                .build();
    }

    @Test
    @DisplayName("rejects HS256-signed token even when key bytes match the public RSA modulus")
    void rejectsHs256ConfusionAttack() throws Exception {
        // Classic HS/RS confusion: attacker signs with HMAC-SHA256 using the public key bytes.
        // The whitelist must reject the alg before signature verification ever happens.
        final var publicKeyEncoded = rsaKeyPair.getPublic().getEncoded();
        final var hmacKey = new SecretKeySpec(publicKeyEncoded, "HmacSHA256");

        final var claims = baseClaims();
        claims.setGeneratedJwtId();

        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(hmacKey);
        jws.setKeyIdHeaderValue(KEY_ID);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        final var token = jws.getCompactSerialization();

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Invalid.class, result);
        // jose4j summary buckets HS256 mismatch under "signature" — defensive: it is at least Invalid.
        final var invalid = (TokenValidationResult.Invalid) result;
        assertTrue(
                invalid.reason().toLowerCase().contains("signature")
                        || invalid.reason().toLowerCase().contains("validation"),
                "expected signature/validation rejection, got: " + invalid.reason());
    }

    @Test
    @DisplayName("rejects unsigned token")
    void rejectsUnsignedToken() throws Exception {
        final var claims = baseClaims();
        claims.setGeneratedJwtId();

        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        jws.setAlgorithmHeaderValue("none");
        final var token = jws.getCompactSerialization();

        when(jwksCache.getKey(JWKS_URI, null)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Invalid.class, result);
    }

    @Test
    @DisplayName("rejects token without a JTI claim")
    void rejectsTokenWithoutJti() throws Exception {
        final var claims = baseClaims();
        // Intentionally do not set a JTI.
        final var token = signRsa(claims);

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Invalid.class, result);
    }

    @Test
    @DisplayName("rejects token whose audience is missing entirely")
    void rejectsTokenWithoutAudience() throws Exception {
        final var claims = baseClaims();
        claims.unsetClaim("aud");
        claims.setGeneratedJwtId();
        final var token = signRsa(claims);

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        // jose4j's exception text for "no aud claim" does not contain the word "audience",
        // so the validator's summarizer maps it to "Token validation failed"; the only
        // contract this test cares about is that the token is rejected outright.
        assertInstanceOf(TokenValidationResult.Invalid.class, result);
    }

    @Test
    @DisplayName("rejects token whose audience does not match the issuer config")
    void rejectsTokenWithWrongAudience() throws Exception {
        final var claims = baseClaims();
        claims.setAudience("attacker-audience");
        claims.setGeneratedJwtId();
        final var token = signRsa(claims);

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Invalid.class, result);
        final var invalid = (TokenValidationResult.Invalid) result;
        assertEquals("Invalid token audience", invalid.reason());
    }

    @Test
    @DisplayName("validation refuses to run when issuer config has no audiences")
    void issuerStateRefusesEmptyAudiences() throws Exception {
        final var brokenConfig = TokenProviderConfig.builder("broken", ISSUER, JWKS_URI)
                .audiences(Set.of())
                .build();
        final var claims = baseClaims();
        claims.setGeneratedJwtId();
        final var token = signRsa(claims);

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, brokenConfig).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Invalid.class, result);
    }

    @Test
    @DisplayName("baseline: a properly-signed token with JTI and matching audience is Valid")
    void baselineValidToken() throws Exception {
        final var claims = baseClaims();
        claims.setGeneratedJwtId();
        final var token = signRsa(claims);

        when(jwksCache.getKey(JWKS_URI, KEY_ID)).thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

        final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(TokenValidationResult.Valid.class, result);
    }

    private JwtClaims baseClaims() {
        final var claims = new JwtClaims();
        claims.setSubject(SUBJECT);
        claims.setIssuer(ISSUER);
        claims.setAudience(AUDIENCE);
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());
        return claims;
    }

    private String signRsa(JwtClaims claims) throws Exception {
        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(rsaKeyPair.getPrivate());
        jws.setKeyIdHeaderValue(KEY_ID);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        return jws.getCompactSerialization();
    }
}

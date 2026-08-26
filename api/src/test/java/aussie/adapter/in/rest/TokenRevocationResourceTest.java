package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.rest.TokenRevocationResource.InspectTokenRequest;
import aussie.adapter.in.rest.TokenRevocationResource.RevokeByTokenRequest;
import aussie.adapter.in.rest.TokenRevocationResource.RevokeTokenRequest;
import aussie.adapter.in.rest.TokenRevocationResource.RevokeUserTokensRequest;
import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.service.auth.TokenRevocationService;
import aussie.core.service.auth.TokenValidationService;

@DisplayName("TokenRevocationResource")
@ExtendWith(MockitoExtension.class)
class TokenRevocationResourceTest {

    @Mock
    private TokenRevocationService revocationService;

    @Mock
    private TokenRevocationConfig config;

    @Mock
    private TokenValidationService tokenValidationService;

    private TokenRevocationResource resource;

    @BeforeEach
    void setUp() {
        resource = new TokenRevocationResource(revocationService, config, tokenValidationService);
    }

    private void whenTokenIsValidated(String token, String jti, Instant expiresAt) {
        var identity = ValidatedIdentity.fromValidatedClaims(
                "test-provider",
                "test-subject",
                "https://issuer.example",
                Set.of("test-audience"),
                Optional.of(Instant.now()),
                Optional.ofNullable(jti),
                Map.of("iat", Instant.now().minusSeconds(1).getEpochSecond()),
                Optional.empty(),
                expiresAt);
        when(tokenValidationService.validate(token))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));
    }

    private String createTestJwt(JwtClaims claims) throws Exception {
        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        jws.setAlgorithmHeaderValue("none");
        return jws.getCompactSerialization();
    }

    @Nested
    @DisplayName("revokeToken")
    class RevokeToken {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeToken("test-jti", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when jti is null")
        void shouldThrowWhenJtiIsNull() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeToken(null, null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when jti is blank")
        void shouldThrowWhenJtiIsBlank() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeToken("  ", null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("succeeds with null request body")
        void shouldSucceedWhenRequestIsNull() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.revokeToken(eq("test-jti"), isNull()))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.revokeToken("test-jti", null).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(revocationService).revokeToken("test-jti", null);
        }

        @Test
        @DisplayName("succeeds with expiresAt and reason in request")
        void shouldSucceedWhenRequestHasExpiresAtAndReason() {
            when(config.enabled()).thenReturn(true);
            var expiresAt = Instant.parse("2026-12-01T00:00:00Z");
            when(revocationService.revokeToken("test-jti", expiresAt))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new RevokeTokenRequest("compromised token", expiresAt);
            var response = resource.revokeToken("test-jti", request).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(revocationService).revokeToken("test-jti", expiresAt);
        }

        @Test
        @DisplayName("passes null expiresAt when request has no expiresAt")
        void shouldPassNullExpiresAtWhenRequestHasNoExpiresAt() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.revokeToken(eq("test-jti"), isNull()))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new RevokeTokenRequest("some reason", null);
            var response = resource.revokeToken("test-jti", request).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(revocationService).revokeToken("test-jti", null);
        }
    }

    @Nested
    @DisplayName("revokeByToken")
    class RevokeByToken {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var request = new RevokeByTokenRequest("some.jwt.token", null);
            var ex = assertThrows(HttpProblem.class, () -> resource.revokeByToken(request));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when request is null")
        void shouldThrowWhenRequestIsNull() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeByToken(null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when token is null")
        void shouldThrowWhenTokenIsNull() {
            when(config.enabled()).thenReturn(true);

            var request = new RevokeByTokenRequest(null, null);
            var ex = assertThrows(HttpProblem.class, () -> resource.revokeByToken(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when token is blank")
        void shouldThrowWhenTokenIsBlank() {
            when(config.enabled()).thenReturn(true);

            var request = new RevokeByTokenRequest("  ", null);
            var ex = assertThrows(HttpProblem.class, () -> resource.revokeByToken(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when token has no jti claim")
        void shouldThrowWhenTokenHasNoJtiClaim() throws Exception {
            when(config.enabled()).thenReturn(true);

            var claims = new JwtClaims();
            claims.setSubject("user123");
            var token = createTestJwt(claims);
            whenTokenIsValidated(token, null, Instant.now().plusSeconds(3600));

            var request = new RevokeByTokenRequest(token, null);
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.revokeByToken(request).await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
            verifyNoInteractions(revocationService);
        }

        @Test
        @DisplayName("throws HttpProblem when validated JTI is blank")
        void shouldThrowWhenValidatedJtiIsBlank() throws Exception {
            when(config.enabled()).thenReturn(true);

            var token = createTestJwt(new JwtClaims());
            whenTokenIsValidated(token, "   ", Instant.now().plusSeconds(3600));

            var request = new RevokeByTokenRequest(token, null);
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.revokeByToken(request).await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
            verifyNoInteractions(revocationService);
        }

        @Test
        @DisplayName("revokes token with jti and expiration")
        @SuppressWarnings("unchecked")
        void shouldRevokeTokenWithJtiAndExpiration() throws Exception {
            when(config.enabled()).thenReturn(true);

            var claims = new JwtClaims();
            claims.setJwtId("jwt-id-123");
            var expTime = NumericDate.fromSeconds(1893456000); // 2030-01-01T00:00:00Z
            claims.setExpirationTime(expTime);
            var token = createTestJwt(claims);

            var expectedExpiry = Instant.ofEpochSecond(1893456000);
            whenTokenIsValidated(token, "jwt-id-123", expectedExpiry);
            when(revocationService.revokeToken("jwt-id-123", expectedExpiry))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new RevokeByTokenRequest(token, "test reason");
            var response = resource.revokeByToken(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("jwt-id-123", entity.get("jti"));
            assertEquals("revoked", entity.get("status"));
            assertNotNull(entity.get("revokedAt"));
            verify(revocationService).revokeToken("jwt-id-123", expectedExpiry);
        }

        @Test
        @DisplayName("uses the validated token expiration")
        @SuppressWarnings("unchecked")
        void shouldRevokeTokenWithValidatedJtiAndExpiration() throws Exception {
            when(config.enabled()).thenReturn(true);

            var claims = new JwtClaims();
            claims.setJwtId("jwt-id-456");
            var token = createTestJwt(claims);

            var expectedExpiry = Instant.now().plusSeconds(3600);
            whenTokenIsValidated(token, "jwt-id-456", expectedExpiry);
            when(revocationService.revokeToken("jwt-id-456", expectedExpiry))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new RevokeByTokenRequest(token, null);
            var response = resource.revokeByToken(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("jwt-id-456", entity.get("jti"));
            assertEquals("revoked", entity.get("status"));
            verify(revocationService).revokeToken("jwt-id-456", expectedExpiry);
        }

        @Test
        @DisplayName("rejects an unsigned token before revocation")
        void shouldRejectUnsignedTokenBeforeRevocation() throws Exception {
            when(config.enabled()).thenReturn(true);

            var claims = new JwtClaims();
            claims.setJwtId("forged-jti");
            var token = createTestJwt(claims);
            when(tokenValidationService.validate(token))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("invalid signature")));

            var request = new RevokeByTokenRequest(token, null);
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.revokeByToken(request).await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
            verifyNoInteractions(revocationService);
        }
    }

    @Nested
    @DisplayName("revokeUserTokens")
    class RevokeUserTokens {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeUserTokens("user123", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when user revocation is disabled")
        void shouldThrowWhenUserRevocationDisabled() {
            when(config.enabled()).thenReturn(true);
            when(config.checkUserRevocation()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeUserTokens("user123", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when userId is null")
        void shouldThrowWhenUserIdIsNull() {
            when(config.enabled()).thenReturn(true);
            when(config.checkUserRevocation()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeUserTokens(null, null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when userId is blank")
        void shouldThrowWhenUserIdIsBlank() {
            when(config.enabled()).thenReturn(true);
            when(config.checkUserRevocation()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.revokeUserTokens("  ", null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("succeeds with null request body")
        void shouldSucceedWhenRequestIsNull() {
            when(config.enabled()).thenReturn(true);
            when(config.checkUserRevocation()).thenReturn(true);
            when(revocationService.revokeAllUserTokens("user123"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.revokeUserTokens("user123", null).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(revocationService).revokeAllUserTokens("user123");
        }

        @Test
        @DisplayName("succeeds with request containing reason")
        void shouldSucceedWithReason() {
            when(config.enabled()).thenReturn(true);
            when(config.checkUserRevocation()).thenReturn(true);
            when(revocationService.revokeAllUserTokens("user123"))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new RevokeUserTokensRequest("password changed");
            var response = resource.revokeUserTokens("user123", request).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(revocationService).revokeAllUserTokens("user123");
        }
    }

    @Nested
    @DisplayName("checkRevocationStatus")
    class CheckRevocationStatus {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.checkRevocationStatus("test-jti"));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when jti is null")
        void shouldThrowWhenJtiIsNull() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.checkRevocationStatus(null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when jti is blank")
        void shouldThrowWhenJtiIsBlank() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.checkRevocationStatus("  "));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns revoked=true when token is revoked")
        @SuppressWarnings("unchecked")
        void shouldReturnRevokedTrueWhenRevoked() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.isTokenRevoked("test-jti"))
                    .thenReturn(Uni.createFrom().item(true));

            var response = resource.checkRevocationStatus("test-jti").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("test-jti", entity.get("jti"));
            assertEquals(true, entity.get("revoked"));
            assertNotNull(entity.get("checkedAt"));
        }

        @Test
        @DisplayName("returns revoked=false when token is not revoked")
        @SuppressWarnings("unchecked")
        void shouldReturnRevokedFalseWhenNotRevoked() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.isTokenRevoked("test-jti"))
                    .thenReturn(Uni.createFrom().item(false));

            var response = resource.checkRevocationStatus("test-jti").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("test-jti", entity.get("jti"));
            assertEquals(false, entity.get("revoked"));
            assertNotNull(entity.get("checkedAt"));
        }
    }

    @Nested
    @DisplayName("listRevokedTokens")
    class ListRevokedTokens {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.listRevokedTokens(null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("uses default limit of 100 when limit is null")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultLimitWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().items("jti-1", "jti-2"));

            var response = resource.listRevokedTokens(null).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
            assertEquals(2, entity.get("count"));
            var revokedTokens = (List<String>) entity.get("revokedTokens");
            assertEquals(2, revokedTokens.size());
        }

        @Test
        @DisplayName("uses default limit of 100 when limit is zero")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultLimitWhenZero() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().empty());

            var response = resource.listRevokedTokens(0).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }

        @Test
        @DisplayName("uses default limit of 100 when limit is negative")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultLimitWhenNegative() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().empty());

            var response = resource.listRevokedTokens(-5).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }

        @Test
        @DisplayName("respects positive limit parameter")
        @SuppressWarnings("unchecked")
        void shouldRespectPositiveLimit() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().items("jti-1"));

            var response = resource.listRevokedTokens(25).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(25, entity.get("limit"));
        }

        @Test
        @DisplayName("caps limit at 100")
        @SuppressWarnings("unchecked")
        void shouldCapLimit() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().empty());

            final var response = resource.listRevokedTokens(101).await().atMost(Duration.ofSeconds(5));

            final var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }
    }

    @Nested
    @DisplayName("listRevokedUsers")
    class ListRevokedUsers {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.listRevokedUsers(null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("uses default limit of 100 when limit is null")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultLimitWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedUsers())
                    .thenReturn(Multi.createFrom().items("user-1", "user-2"));

            var response = resource.listRevokedUsers(null).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
            assertEquals(2, entity.get("count"));
            var revokedUsers = (List<String>) entity.get("revokedUsers");
            assertEquals(2, revokedUsers.size());
        }

        @Test
        @DisplayName("respects positive limit parameter")
        @SuppressWarnings("unchecked")
        void shouldRespectPositiveLimit() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedUsers())
                    .thenReturn(Multi.createFrom().items("user-1"));

            var response = resource.listRevokedUsers(50).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(50, entity.get("limit"));
        }

        @Test
        @DisplayName("caps limit at 100")
        @SuppressWarnings("unchecked")
        void shouldCapLimit() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.streamAllRevokedUsers())
                    .thenReturn(Multi.createFrom().empty());

            final var response = resource.listRevokedUsers(101).await().atMost(Duration.ofSeconds(5));

            final var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }
    }

    @Nested
    @DisplayName("inspectToken")
    class InspectToken {

        @Test
        @DisplayName("throws HttpProblem when request is null")
        void shouldThrowWhenRequestIsNull() {
            var ex = assertThrows(HttpProblem.class, () -> resource.inspectToken(null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when token is null")
        void shouldThrowWhenTokenIsNull() {
            var request = new InspectTokenRequest(null);
            var ex = assertThrows(HttpProblem.class, () -> resource.inspectToken(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when token is blank")
        void shouldThrowWhenTokenIsBlank() {
            var request = new InspectTokenRequest("  ");
            var ex = assertThrows(HttpProblem.class, () -> resource.inspectToken(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns all claims for token with full claims")
        @SuppressWarnings("unchecked")
        void shouldReturnAllClaimsForFullToken() throws Exception {
            var claims = new JwtClaims();
            claims.setJwtId("jwt-id-full");
            claims.setSubject("user-subject");
            claims.setIssuer("test-issuer");
            claims.setAudience("test-audience");
            claims.setIssuedAt(NumericDate.fromSeconds(1704067200)); // 2024-01-01T00:00:00Z
            claims.setExpirationTime(NumericDate.fromSeconds(1735689600)); // 2025-01-01T00:00:00Z
            claims.setNotBefore(NumericDate.fromSeconds(1704067200)); // 2024-01-01T00:00:00Z
            claims.setClaim("custom-claim", "custom-value");
            var token = createTestJwt(claims);

            var request = new InspectTokenRequest(token);
            var response = resource.inspectToken(request);

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("jwt-id-full", entity.get("jti"));
            assertEquals("user-subject", entity.get("subject"));
            assertEquals("test-issuer", entity.get("issuer"));
            assertNotNull(entity.get("audience"));
            assertNotNull(entity.get("issuedAt"));
            assertNotNull(entity.get("expiresAt"));
            assertNotNull(entity.get("notBefore"));
            var otherClaims = (Map<String, Object>) entity.get("otherClaims");
            assertNotNull(otherClaims);
            assertEquals("custom-value", otherClaims.get("custom-claim"));
        }

        @Test
        @DisplayName("returns null timestamps for token with minimal claims")
        @SuppressWarnings("unchecked")
        void shouldReturnNullTimestampsForMinimalToken() throws Exception {
            var claims = new JwtClaims();
            claims.setJwtId("jwt-id-minimal");
            claims.setSubject("user-minimal");
            var token = createTestJwt(claims);

            var request = new InspectTokenRequest(token);
            var response = resource.inspectToken(request);

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("jwt-id-minimal", entity.get("jti"));
            assertEquals("user-minimal", entity.get("subject"));
            assertNull(entity.get("issuedAt"));
            assertNull(entity.get("expiresAt"));
            assertNull(entity.get("notBefore"));
            assertFalse(entity.containsKey("otherClaims"));
        }

        @Test
        @DisplayName("throws HttpProblem for invalid token format")
        void shouldThrowWhenTokenFormatIsInvalid() {
            var request = new InspectTokenRequest("completely-invalid-jwt");
            var ex = assertThrows(HttpProblem.class, () -> resource.inspectToken(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("includes otherClaims when extra claims are present")
        @SuppressWarnings("unchecked")
        void shouldIncludeOtherClaimsWhenPresent() throws Exception {
            var claims = new JwtClaims();
            claims.setJwtId("jwt-with-extras");
            claims.setClaim("role", "admin");
            claims.setClaim("scope", "read write");
            var token = createTestJwt(claims);

            var request = new InspectTokenRequest(token);
            var response = resource.inspectToken(request);

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            var otherClaims = (Map<String, Object>) entity.get("otherClaims");
            assertNotNull(otherClaims);
            assertEquals("admin", otherClaims.get("role"));
            assertEquals("read write", otherClaims.get("scope"));
            // Standard claims should NOT be in otherClaims
            assertFalse(otherClaims.containsKey("jti"));
            assertFalse(otherClaims.containsKey("sub"));
        }
    }

    @Nested
    @DisplayName("rebuildBloomFilter")
    class RebuildBloomFilter {

        @Test
        @DisplayName("throws HttpProblem when feature is disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.rebuildBloomFilter());
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns success response after rebuild")
        @SuppressWarnings("unchecked")
        void shouldReturnSuccessAfterRebuild() {
            when(config.enabled()).thenReturn(true);
            when(revocationService.rebuildBloomFilter())
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.rebuildBloomFilter().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("rebuilt", entity.get("status"));
            assertNotNull(entity.get("rebuiltAt"));
            verify(revocationService).rebuildBloomFilter();
        }
    }
}

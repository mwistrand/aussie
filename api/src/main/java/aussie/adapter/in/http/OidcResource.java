package aussie.adapter.in.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.OidcRefreshTokenRepository;
import aussie.core.service.auth.OidcTokenExchangeProviderRegistry;
import aussie.core.service.auth.PkceService;

/**
 * REST endpoints for OIDC authorization flows with PKCE support.
 *
 * <p>Implements RFC 7636 (PKCE) to protect authorization code flows against
 * interception attacks. All authorization requests must include PKCE parameters
 * when PKCE is required (default).
 *
 * @see <a href="https://tools.ietf.org/html/rfc7636">RFC 7636 - PKCE</a>
 */
@Path("/auth/oidc")
@Produces(MediaType.APPLICATION_JSON)
public class OidcResource {

    private static final Logger LOG = Logger.getLogger(OidcResource.class);

    private final PkceService pkceService;
    private final PkceConfig pkceConfig;
    private final OidcConfig oidcConfig;
    private final SessionConfig sessionConfig;
    private final OidcTokenExchangeProviderRegistry tokenExchangeRegistry;
    private final SessionManagement sessionManagement;
    private final OidcRefreshTokenRepository refreshTokenRepository;

    @Inject
    public OidcResource(
            PkceService pkceService,
            PkceConfig pkceConfig,
            OidcConfig oidcConfig,
            SessionConfig sessionConfig,
            OidcTokenExchangeProviderRegistry tokenExchangeRegistry,
            SessionManagement sessionManagement,
            OidcRefreshTokenRepository refreshTokenRepository) {
        this.pkceService = pkceService;
        this.pkceConfig = pkceConfig;
        this.oidcConfig = oidcConfig;
        this.sessionConfig = sessionConfig;
        this.tokenExchangeRegistry = tokenExchangeRegistry;
        this.sessionManagement = sessionManagement;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Initiate an OIDC authorization request.
     *
     * <p>This endpoint validates PKCE parameters and stores the challenge for
     * later verification during token exchange. It then redirects to the
     * configured identity provider.
     *
     * @param redirectUri The URI to redirect to after authentication
     * @param codeChallenge The PKCE code_challenge (required when PKCE is enabled)
     * @param codeChallengeMethod The challenge method (must be "S256")
     * @param clientState Optional client-provided state for CSRF protection
     * @param idpUrl The identity provider authorization URL
     * @return Redirect to identity provider or error response
     */
    @GET
    @Path("/authorize")
    public Uni<Response> authorize(
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod,
            @QueryParam("state") String clientState,
            @QueryParam("idp_url") String idpUrl) {

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate redirect URI
        if (redirectUri == null || redirectUri.isBlank()) {
            throw GatewayProblem.badRequest("redirect_uri is required");
        }
        validateUrl(redirectUri, "redirect_uri");

        // Validate IdP URL
        if (idpUrl == null || idpUrl.isBlank()) {
            throw GatewayProblem.badRequest("idp_url is required");
        }
        validateUrl(idpUrl, "idp_url");

        // Validate PKCE parameters
        if (pkceService.isRequired()) {
            if (codeChallenge == null || codeChallenge.isBlank()) {
                LOG.debug("PKCE required but code_challenge not provided");
                throw GatewayProblem.badRequest("PKCE with S256 challenge method is required");
            }

            if (!pkceService.isValidChallengeMethod(codeChallengeMethod)) {
                LOG.debugf("Invalid challenge method: %s", codeChallengeMethod);
                throw GatewayProblem.badRequest("Only S256 challenge method is supported");
            }
        }

        // Generate state parameter for CSRF protection
        final var state = pkceService.generateState();

        // Store PKCE challenge if provided
        Uni<Void> storeChallenge;
        if (codeChallenge != null && !codeChallenge.isBlank()) {
            storeChallenge = pkceService.storeChallenge(state, codeChallenge);
        } else {
            storeChallenge = Uni.createFrom().voidItem();
        }

        return storeChallenge.map(v -> {
            LOG.debugf("Initiating OIDC authorization with state: %s", state);

            // Build IdP authorization URL
            String authUrl = buildIdpUrl(idpUrl, state, redirectUri, clientState);

            return Response.seeOther(URI.create(authUrl)).build();
        });
    }

    /**
     * Exchange authorization code for tokens with PKCE verification.
     *
     * <p>This endpoint verifies the PKCE code_verifier against the stored
     * challenge before completing the token exchange.
     *
     * @param code The authorization code from the IdP
     * @param codeVerifier The PKCE code_verifier (required when PKCE was used)
     * @param state The state parameter from the authorization request
     * @param redirectUri The redirect URI (must match the authorization request)
     * @return Token response or error
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Uni<Response> exchangeToken(
            @FormParam("code") String code,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("state") String state,
            @FormParam("redirect_uri") String redirectUri) {

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate required parameters
        if (code == null || code.isBlank()) {
            throw GatewayProblem.badRequest("code is required");
        }

        if (state == null || state.isBlank()) {
            throw GatewayProblem.badRequest("state is required");
        }

        // Verify PKCE
        if (pkceService.isRequired()) {
            if (codeVerifier == null || codeVerifier.isBlank()) {
                LOG.debug("PKCE required but code_verifier not provided");
                throw GatewayProblem.badRequest("code_verifier is required");
            }
        }

        // Verify PKCE if verifier is provided
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            return pkceService.verifyChallenge(state, codeVerifier).flatMap(valid -> {
                if (!valid) {
                    LOG.warnf("PKCE verification failed for state: %s", state);
                    throw GatewayProblem.badRequest("PKCE verification failed");
                }

                LOG.debugf("PKCE verification successful for state: %s", state);
                return completeTokenExchange(code, redirectUri, Optional.of(codeVerifier));
            });
        }

        // No verifier provided and PKCE is optional - proceed with token exchange
        return completeTokenExchange(code, redirectUri, Optional.empty());
    }

    /**
     * Complete the token exchange with the identity provider.
     *
     * <p>Exchanges the authorization code for tokens, optionally creates
     * a session, and stores refresh tokens if configured.
     *
     * @param code The authorization code from the IdP
     * @param redirectUri The redirect URI used in the authorization request
     * @param codeVerifier Optional PKCE code verifier
     * @return Token response with session info if session creation is enabled
     */
    private Uni<Response> completeTokenExchange(String code, String redirectUri, Optional<String> codeVerifier) {
        // Validate token exchange is enabled
        if (!oidcConfig.tokenExchange().enabled()) {
            throw GatewayProblem.featureDisabled("OIDC Token Exchange");
        }

        // Validate required configuration
        final var tokenEndpoint = oidcConfig
                .tokenExchange()
                .tokenEndpoint()
                .orElseThrow(() -> GatewayProblem.internalError("OIDC token endpoint not configured"));
        final var clientId = oidcConfig
                .tokenExchange()
                .clientId()
                .orElseThrow(() -> GatewayProblem.internalError("OIDC client ID not configured"));
        final var clientSecret = oidcConfig.tokenExchange().clientSecret().orElse(null);

        // Parse client auth method
        final var authMethod = parseClientAuthMethod(oidcConfig.tokenExchange().clientAuthMethod());

        // Build scopes string
        final var scopes = oidcConfig.tokenExchange().scopes();
        final var scopesStr = scopes.isEmpty() ? Optional.<String>empty() : Optional.of(String.join(" ", scopes));

        // Build token exchange request
        final var request = new OidcTokenExchangeRequest(
                code, redirectUri, codeVerifier, tokenEndpoint, clientId, clientSecret, authMethod, scopesStr);

        LOG.debugf("Token exchange initiated for code: %s", code.substring(0, Math.min(8, code.length())) + "...");

        // Execute token exchange
        return tokenExchangeRegistry
                .getProvider()
                .exchange(request)
                .flatMap(tokenResponse -> handleTokenResponse(tokenResponse));
    }

    /**
     * Handle the token response from the IdP.
     *
     * <p>Creates a session if configured and stores refresh tokens.
     */
    private Uni<Response> handleTokenResponse(OidcTokenExchangeResponse tokenResponse) {
        // Check if session creation is enabled
        final var shouldCreateSession = sessionConfig.enabled()
                && oidcConfig.tokenExchange().createSession()
                && tokenResponse.idToken().isPresent();

        if (shouldCreateSession) {
            return createSessionFromToken(tokenResponse);
        }

        // Return tokens directly without session creation
        return buildTokenResponse(tokenResponse, Optional.empty());
    }

    /**
     * Create a session from the ID token claims.
     */
    private Uni<Response> createSessionFromToken(OidcTokenExchangeResponse tokenResponse) {
        // Extract claims from ID token (simple JWT parsing - no validation here)
        // Full validation should happen via TokenValidationService if configured
        final var idToken = tokenResponse.idToken().orElseThrow();
        final var claims = parseIdTokenClaims(idToken);

        final var userId = claims.getOrDefault("sub", "unknown").toString();
        final var issuer = claims.getOrDefault("iss", "unknown").toString();

        // Extract permissions/roles from claims
        final var permissions = extractPermissions(claims);

        return sessionManagement
                .createSession(userId, issuer, claims, permissions, null, null)
                .flatMap(session -> {
                    // Store refresh token with session ID
                    Uni<Void> storeRefresh = Uni.createFrom().voidItem();
                    if (oidcConfig.tokenExchange().refreshToken().store()
                            && tokenResponse.refreshToken().isPresent()) {
                        final var ttl =
                                oidcConfig.tokenExchange().refreshToken().defaultTtl();
                        storeRefresh = refreshTokenRepository.store(
                                session.id(), tokenResponse.refreshToken().get(), ttl);
                    }

                    return storeRefresh.replaceWith(session);
                })
                .flatMap(session -> buildTokenResponse(tokenResponse, Optional.of(session.id())));
    }

    /**
     * Build the final HTTP response from the token exchange result.
     */
    private Uni<Response> buildTokenResponse(OidcTokenExchangeResponse tokenResponse, Optional<String> sessionId) {
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("access_token", tokenResponse.accessToken());
        responseBody.put("token_type", tokenResponse.tokenType());
        responseBody.put("expires_in", tokenResponse.expiresIn());

        tokenResponse.idToken().ifPresent(idToken -> responseBody.put("id_token", idToken));
        tokenResponse.scope().ifPresent(scope -> responseBody.put("scope", scope));

        // Include session ID if created
        sessionId.ifPresent(id -> responseBody.put("session_id", id));

        // Note: refresh_token is intentionally not returned to the client
        // as it's stored server-side for automatic renewal

        LOG.debugf(
                "Token exchange successful, expires_in: %d, session: %s",
                tokenResponse.expiresIn(), sessionId.orElse("none"));

        return Uni.createFrom().item(Response.ok(responseBody).build());
    }

    /**
     * Parse ID token claims from a JWT.
     *
     * <p>This is a simple parser that extracts the payload without validation.
     * For production use with untrusted tokens, validation via JWKS should be performed.
     */
    private Map<String, Object> parseIdTokenClaims(String idToken) {
        try {
            final var parts = idToken.split("\\.");
            if (parts.length != 3) {
                LOG.warn("Invalid ID token format");
                return Map.of();
            }

            final var payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            return new io.vertx.core.json.JsonObject(payload).getMap();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse ID token claims");
            return Map.of();
        }
    }

    /**
     * Extract permissions/roles from token claims.
     */
    private Set<String> extractPermissions(Map<String, Object> claims) {
        // Common claim names for roles/permissions
        final var roleClaimNames = Set.of("roles", "groups", "permissions", "scope");

        for (String claimName : roleClaimNames) {
            final var value = claims.get(claimName);
            if (value instanceof java.util.Collection<?> collection) {
                return collection.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(java.util.stream.Collectors.toSet());
            } else if (value instanceof String str) {
                return Set.of(str.split("[\\s,]+"));
            }
        }

        return Set.of();
    }

    /**
     * Parse client authentication method from configuration string.
     */
    private ClientAuthMethod parseClientAuthMethod(String method) {
        return switch (method.toLowerCase().replace("-", "_")) {
            case "client_secret_post" -> ClientAuthMethod.CLIENT_SECRET_POST;
            default -> ClientAuthMethod.CLIENT_SECRET_BASIC;
        };
    }

    /**
     * Build the IdP authorization URL with all required parameters.
     */
    private String buildIdpUrl(String baseUrl, String state, String redirectUri, String clientState) {
        StringBuilder url = new StringBuilder(baseUrl);

        // Append ? or & depending on whether URL already has query params
        url.append(baseUrl.contains("?") ? "&" : "?");
        url.append("state=").append(urlEncode(state));
        url.append("&redirect_uri=").append(urlEncode(redirectUri));

        // Include original client state if provided (for client-side CSRF)
        if (clientState != null && !clientState.isBlank()) {
            url.append("&client_state=").append(urlEncode(clientState));
        }

        return url.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void validateUrl(String url, String paramName) {
        try {
            final var uri = URI.create(url);
            if (uri.getScheme() == null
                    || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                throw GatewayProblem.badRequest(paramName + " must use http or https scheme");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw GatewayProblem.badRequest(paramName + " must have a valid host");
            }
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.badRequest(paramName + " is not a valid URL");
        }
    }
}

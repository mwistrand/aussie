package aussie.adapter.in.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.OidcAuthorizationTransaction;
import aussie.core.model.auth.OidcAuthorizationTransaction.ClientType;
import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.OidcRefreshTokenRepository;
import aussie.core.service.auth.OidcTokenExchangeProviderRegistry;
import aussie.core.service.auth.PkceService;
import aussie.core.service.auth.TokenValidationService;

/**
 * REST endpoints for OIDC authorization flows with PKCE support.
 *
 * <p>Implements RFC 7636 (PKCE) to protect authorization code flows against
 * interception attacks. All authorization requests must include PKCE parameters.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7636">RFC 7636 - PKCE</a>
 */
@Path("/auth/oidc")
@Produces(MediaType.APPLICATION_JSON)
public class OidcResource {

    private static final Logger LOG = Logger.getLogger(OidcResource.class);
    private static final Pattern AUTHORIZATION_CODE_PATTERN = Pattern.compile("[\\x20-\\x7E]{1,4096}");

    private final PkceService pkceService;
    private final PkceConfig pkceConfig;
    private final OidcConfig oidcConfig;
    private final SessionConfig sessionConfig;
    private final OidcTokenExchangeProviderRegistry tokenExchangeRegistry;
    private final SessionManagement sessionManagement;
    private final OidcRefreshTokenRepository refreshTokenRepository;
    private final TokenValidationService tokenValidationService;
    private final SessionCookieManager cookieManager;

    @Inject
    public OidcResource(
            PkceService pkceService,
            PkceConfig pkceConfig,
            OidcConfig oidcConfig,
            SessionConfig sessionConfig,
            OidcTokenExchangeProviderRegistry tokenExchangeRegistry,
            SessionManagement sessionManagement,
            OidcRefreshTokenRepository refreshTokenRepository,
            TokenValidationService tokenValidationService,
            SessionCookieManager cookieManager) {
        this.pkceService = pkceService;
        this.pkceConfig = pkceConfig;
        this.oidcConfig = oidcConfig;
        this.sessionConfig = sessionConfig;
        this.tokenExchangeRegistry = tokenExchangeRegistry;
        this.sessionManagement = sessionManagement;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenValidationService = tokenValidationService;
        this.cookieManager = cookieManager;
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
     * @return Redirect to identity provider or error response
     */
    @GET
    @Path("/authorize")
    public Uni<Response> authorize(
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("code_challenge") String codeChallenge,
            @QueryParam("code_challenge_method") String codeChallengeMethod) {

        requirePublicEndpointsEnabled();
        requireTokenExchangeEnabled();

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate redirect URI
        if (redirectUri == null || redirectUri.isBlank()) {
            throw GatewayProblem.badRequest("redirect_uri is required");
        }
        validateRedirectUri(redirectUri);
        validateUrl(redirectUri, "redirect_uri", false);
        final var authorizationEndpoint =
                configuredValue(oidcConfig.tokenExchange().authorizationEndpoint(), "OIDC authorization endpoint");
        validateUrl(authorizationEndpoint, "OIDC authorization endpoint", true);
        final var providerId = configuredValue(oidcConfig.tokenExchange().providerId(), "OIDC provider ID");

        // Validate PKCE parameters
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw GatewayProblem.badRequest("PKCE with S256 challenge method is required");
        }
        if (!pkceService.isValidChallengeMethod(codeChallengeMethod)) {
            throw GatewayProblem.badRequest("Only S256 challenge method is supported");
        }
        if (!pkceService.isValidCodeChallenge(codeChallenge)) {
            throw GatewayProblem.badRequest("code_challenge is not a valid S256 challenge");
        }

        final var state = pkceService.generateState();
        final var nonce = pkceService.generateNonce();
        final var now = Instant.now();
        final var transactionTtl = configuredTransactionTtl();
        final var transaction = new OidcAuthorizationTransaction(
                providerId,
                redirectUri,
                codeChallenge,
                nonce,
                sessionConfig.enabled() && oidcConfig.tokenExchange().createSession()
                        ? ClientType.SESSION
                        : ClientType.PUBLIC,
                now,
                now.plus(transactionTtl));

        return pkceService
                .storeTransaction(state, transaction)
                .replaceWith(Response.seeOther(URI.create(buildIdpUrl(authorizationEndpoint, state, transaction)))
                        .build());
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

        requirePublicEndpointsEnabled();
        requireTokenExchangeEnabled();

        if (!pkceConfig.enabled()) {
            throw GatewayProblem.featureDisabled("PKCE");
        }

        // Validate required parameters
        if (code == null || !AUTHORIZATION_CODE_PATTERN.matcher(code).matches()) {
            throw GatewayProblem.badRequest("code is invalid");
        }

        if (!pkceService.isValidState(state)) {
            throw GatewayProblem.badRequest("state is invalid");
        }

        if (!pkceService.isValidCodeVerifier(codeVerifier)) {
            throw GatewayProblem.badRequest("code_verifier is invalid");
        }

        return pkceService.consumeTransaction(state).flatMap(transaction -> {
            if (transaction.isEmpty()) {
                throw GatewayProblem.badRequest("Invalid or already-used OIDC state");
            }
            final var stored = transaction.get();
            if (stored.isExpired(Instant.now())) {
                throw GatewayProblem.badRequest("OIDC transaction has expired");
            }
            if (redirectUri != null && !stored.redirectUri().equals(redirectUri)) {
                throw GatewayProblem.badRequest("redirect_uri does not match the authorization request");
            }
            if (!pkceService.verifyChallenge(stored, codeVerifier)) {
                throw GatewayProblem.badRequest("PKCE verification failed");
            }
            return completeTokenExchange(code, stored, codeVerifier);
        });
    }

    /**
     * Complete the token exchange with the identity provider.
     *
     * <p>Exchanges the authorization code for tokens, optionally creates
     * a session, and stores refresh tokens if configured.
     *
     * @param code The authorization code from the IdP
     * @param transaction The consumed authorization transaction
     * @param codeVerifier PKCE code verifier
     * @return Public-client tokens or a cookie-only session response
     */
    private Uni<Response> completeTokenExchange(
            String code, OidcAuthorizationTransaction transaction, String codeVerifier) {
        // Validate required configuration
        final var tokenEndpoint = oidcConfig
                .tokenExchange()
                .tokenEndpoint()
                .orElseThrow(() -> GatewayProblem.internalError("OIDC token endpoint not configured"));
        validateUrl(tokenEndpoint, "OIDC token endpoint", true);
        final var clientId = oidcConfig
                .tokenExchange()
                .clientId()
                .orElseThrow(() -> GatewayProblem.internalError("OIDC client ID not configured"));
        final var clientSecret = oidcConfig.tokenExchange().clientSecret().orElse(null);

        // Parse client auth method
        final var authMethod = parseClientAuthMethod(oidcConfig.tokenExchange().clientAuthMethod());

        // Build scopes string
        final var scopesStr = Optional.of(configuredScopes());

        // Build token exchange request
        final var request = new OidcTokenExchangeRequest(
                code,
                transaction.redirectUri(),
                Optional.of(codeVerifier),
                tokenEndpoint,
                clientId,
                clientSecret,
                authMethod,
                scopesStr);

        // Execute token exchange
        return tokenExchangeRegistry
                .getProvider()
                .exchange(request)
                .flatMap(tokenResponse -> handleTokenResponse(tokenResponse, transaction, clientId));
    }

    private void requirePublicEndpointsEnabled() {
        if (!oidcConfig.publicEndpointsEnabled()) {
            throw GatewayProblem.featureDisabled("Public OIDC helpers");
        }
    }

    private void requireTokenExchangeEnabled() {
        if (!oidcConfig.tokenExchange().enabled()) {
            throw GatewayProblem.featureDisabled("OIDC Token Exchange");
        }
    }

    /**
     * Handle the token response from the IdP.
     *
     * <p>Validates the required ID token, then either returns public-client tokens
     * or creates a cookie-only session.
     */
    private Uni<Response> handleTokenResponse(
            OidcTokenExchangeResponse tokenResponse, OidcAuthorizationTransaction transaction, String clientId) {
        final var idToken =
                tokenResponse.idToken().orElseThrow(() -> GatewayProblem.badGateway("IdP response missing ID token"));

        return validateIdToken(idToken, transaction, clientId).flatMap(identity -> {
            if (transaction.clientType() == ClientType.SESSION) {
                if (!sessionConfig.enabled()) {
                    throw GatewayProblem.internalError("Session support is not enabled");
                }
                return createSessionFromIdentity(tokenResponse, identity);
            }
            return buildTokenResponse(tokenResponse);
        });
    }

    /**
     * Create a session from the ID token claims.
     */
    private Uni<Response> createSessionFromIdentity(
            OidcTokenExchangeResponse tokenResponse, ValidatedIdentity identity) {
        return sessionManagement
                .createSession(identity, null, null)
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
                .map(session -> Response.noContent()
                        .cookie(cookieManager.createResponseCookie(session))
                        .build());
    }

    private Uni<ValidatedIdentity> validateIdToken(
            String idToken, OidcAuthorizationTransaction transaction, String clientId) {
        return tokenValidationService.validate(idToken).map(result -> {
            if (!(result instanceof TokenValidationResult.Valid valid)) {
                throw GatewayProblem.unauthorized("Invalid ID token");
            }
            final var identity = valid.identity();
            final var claims = identity.claims();
            if (!transaction.providerId().equals(identity.providerId())
                    || !identity.audiences().contains(clientId)
                    || !transaction.nonce().equals(claims.get("nonce"))) {
                throw GatewayProblem.unauthorized("ID token is not bound to the authorization request");
            }
            final var authorizedParty = claims.get("azp");
            if ((identity.audiences().size() > 1 && !(authorizedParty instanceof String))
                    || (authorizedParty != null && !clientId.equals(authorizedParty))) {
                throw GatewayProblem.unauthorized("Invalid ID token authorized party");
            }
            final var issuedAt = instantClaim(claims.get("iat"));
            final var authenticatedAtClaim = claims.get("auth_time");
            final var authenticatedAt = instantClaim(authenticatedAtClaim);
            final var futureLimit = Instant.now().plusSeconds(30);
            if (issuedAt.isEmpty()
                    || issuedAt.get().isAfter(futureLimit)
                    || (authenticatedAtClaim != null && authenticatedAt.isEmpty())
                    || authenticatedAt
                            .filter(value -> value.isAfter(futureLimit))
                            .isPresent()) {
                throw GatewayProblem.unauthorized("Invalid ID token time claims");
            }
            return identity;
        });
    }

    /**
     * Build the public-client HTTP response from the token exchange result.
     */
    private Uni<Response> buildTokenResponse(OidcTokenExchangeResponse tokenResponse) {
        final var responseBody = new HashMap<String, Object>();
        responseBody.put("access_token", tokenResponse.accessToken());
        responseBody.put("token_type", tokenResponse.tokenType());
        responseBody.put("expires_in", tokenResponse.expiresIn());

        tokenResponse.idToken().ifPresent(idToken -> responseBody.put("id_token", idToken));
        tokenResponse.scope().ifPresent(scope -> responseBody.put("scope", scope));

        // Note: refresh_token is intentionally not returned to the client
        // as it's stored server-side for automatic renewal

        LOG.debugf("Token exchange successful, expires_in: %d", tokenResponse.expiresIn());

        return Uni.createFrom().item(Response.ok(responseBody).build());
    }

    /**
     * Parse client authentication method from configuration string.
     */
    private ClientAuthMethod parseClientAuthMethod(String method) {
        return switch (method.toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "client_secret_basic" -> ClientAuthMethod.CLIENT_SECRET_BASIC;
            case "client_secret_post" -> ClientAuthMethod.CLIENT_SECRET_POST;
            default -> throw GatewayProblem.internalError("Unsupported OIDC client authentication method");
        };
    }

    /**
     * Build the IdP authorization URL with all required parameters.
     */
    private String buildIdpUrl(String baseUrl, String state, OidcAuthorizationTransaction transaction) {
        final var url = new StringBuilder(baseUrl);

        // Append ? or & depending on whether URL already has query params
        url.append(baseUrl.contains("?") ? "&" : "?");
        url.append("state=").append(urlEncode(state));
        url.append("&nonce=").append(urlEncode(transaction.nonce()));
        url.append("&response_type=code");
        url.append("&client_id=")
                .append(urlEncode(configuredValue(oidcConfig.tokenExchange().clientId(), "OIDC client ID")));
        url.append("&redirect_uri=").append(urlEncode(transaction.redirectUri()));
        url.append("&scope=").append(urlEncode(configuredScopes()));
        url.append("&code_challenge=").append(urlEncode(transaction.codeChallenge()));
        url.append("&code_challenge_method=S256");

        return url.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void validateUrl(String url, String paramName, boolean configured) {
        try {
            final var uri = URI.create(url);
            if (uri.getScheme() == null
                    || (!uri.getScheme().equalsIgnoreCase("http")
                            && !uri.getScheme().equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid HTTP URL");
            }
        } catch (IllegalArgumentException e) {
            if (configured) {
                throw GatewayProblem.internalError(paramName + " is not a valid HTTP(S) URL");
            }
            throw GatewayProblem.badRequest(paramName + " is not a valid URL");
        }
    }

    private void validateRedirectUri(String redirectUri) {
        final var allowed = oidcConfig.tokenExchange().redirectUris().orElse(Set.of());
        if (!allowed.contains(redirectUri)) {
            throw GatewayProblem.badRequest("redirect_uri is not registered");
        }
    }

    private String configuredValue(Optional<String> value, String name) {
        return value.filter(configured -> !configured.isBlank())
                .orElseThrow(() -> GatewayProblem.internalError(name + " is not configured"));
    }

    private String configuredScopes() {
        final var scopes = oidcConfig.tokenExchange().scopes();
        if (scopes == null
                || !scopes.contains("openid")
                || scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw GatewayProblem.internalError("OIDC scopes must include openid and cannot be blank");
        }
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    private Duration configuredTransactionTtl() {
        final var ttl = pkceConfig.challengeTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw GatewayProblem.internalError("OIDC transaction TTL must be at least one second");
        }
        return ttl;
    }

    private Optional<Instant> instantClaim(Object value) {
        try {
            if (value instanceof Number number) {
                return Optional.of(Instant.ofEpochSecond(number.longValue()));
            }
            return value == null
                    ? Optional.empty()
                    : Optional.of(Instant.ofEpochSecond(Long.parseLong(value.toString())));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}

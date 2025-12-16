package aussie.core.service.gateway;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionToken;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.TokenIssuanceService;
import aussie.core.service.auth.TokenValidationService;
import aussie.core.service.session.SessionTokenService;

/**
 * Service that handles per-route authentication decisions.
 *
 * <p>
 * Coordinates between token validation and issuance to determine if a request
 * should be allowed to proceed and what token to forward to the backend.
 */
@ApplicationScoped
public class RouteAuthenticationService {

    private static final Logger LOG = Logger.getLogger(RouteAuthenticationService.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenValidationService validationService;
    private final TokenIssuanceService issuanceService;
    private final SessionManagement sessionManagement;
    private final SessionTokenService sessionTokenService;
    private final SessionConfig sessionConfig;

    @Inject
    public RouteAuthenticationService(
            TokenValidationService validationService,
            TokenIssuanceService issuanceService,
            SessionManagement sessionManagement,
            SessionTokenService sessionTokenService,
            SessionConfig sessionConfig) {
        this.validationService = validationService;
        this.issuanceService = issuanceService;
        this.sessionManagement = sessionManagement;
        this.sessionTokenService = sessionTokenService;
        this.sessionConfig = sessionConfig;
    }

    /**
     * Authenticate a request for the matched route.
     *
     * @param request the gateway request
     * @param route   the matched route
     * @return authentication result
     */
    public Uni<RouteAuthResult> authenticate(GatewayRequest request, RouteMatch route) {
        LOG.debugv(
                "RouteAuthenticationService.authenticate() called for path: {0}",
                route.endpointConfig().path());
        boolean authRequired = route.authRequired();
        LOG.debugv("Auth required: {0}", authRequired);

        if (!authRequired) {
            LOG.debugv(
                    "Route {0} does not require authentication",
                    route.endpointConfig().path());
            return Uni.createFrom().item(new RouteAuthResult.NotRequired());
        }

        // Extract both auth methods
        String bearerToken = extractBearerToken(request);
        Optional<String> sessionId = extractSessionCookie(request);
        LOG.debugv(
                "Bearer token present: {0}, Session cookie present: {1}", bearerToken != null, sessionId.isPresent());

        // Check if both auth methods are present - this is not allowed
        if (bearerToken != null && sessionId.isPresent()) {
            LOG.warnf(
                    "Both bearer token and session cookie provided for route %s",
                    route.endpointConfig().path());
            return Uni.createFrom().item(new RouteAuthResult.BadRequest("Only one authentication method allowed"));
        }

        // If session cookie is present, validate and convert to bearer token
        if (sessionId.isPresent()) {
            LOG.debugv(
                    "Authenticating with session cookie for route {0}",
                    route.endpointConfig().path());
            return authenticateWithSession(sessionId.get(), route);
        }

        // Fall back to bearer token authentication
        if (bearerToken == null) {
            LOG.debugv(
                    "No authentication provided for protected route {0}",
                    route.endpointConfig().path());
            return Uni.createFrom().item(new RouteAuthResult.Unauthorized("Authentication required"));
        }

        return validationService
                .validate(bearerToken)
                .flatMap(validationResult -> handleValidationResult(validationResult, route));
    }

    private Uni<RouteAuthResult> authenticateWithSession(String sessionId, RouteMatch route) {
        return sessionManagement.getSession(sessionId).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                LOG.debugv(
                        "Session {0} not found or expired for route {1}",
                        sessionId, route.endpointConfig().path());
                return new RouteAuthResult.Unauthorized("Session invalid or expired");
            }

            Session session = sessionOpt.get();

            // Generate a JWS token from the session
            if (!sessionTokenService.isEnabled() || !sessionTokenService.isSigningAvailable()) {
                LOG.warnv(
                        "Session token generation not available for route {0}",
                        route.endpointConfig().path());
                return new RouteAuthResult.Unauthorized("Session authentication not configured");
            }

            try {
                SessionToken sessionToken = sessionTokenService.generateToken(session);
                AussieToken aussieToken = new AussieToken(
                        sessionToken.token(), session.userId(), sessionToken.expiresAt(), session.claims());

                LOG.debugv(
                        "Authenticated session {0} for route {1}, subject: {2}",
                        sessionId, route.endpointConfig().path(), session.userId());

                // Include session ID for logout tracking (e.g., WebSocket disconnect on logout)
                return new RouteAuthResult.Authenticated(aussieToken, Optional.of(sessionId));
            } catch (SessionTokenService.SessionTokenException e) {
                LOG.errorv(
                        e,
                        "Failed to generate session token for route {0}",
                        route.endpointConfig().path());
                return new RouteAuthResult.Unauthorized("Session token generation failed");
            }
        });
    }

    private Uni<RouteAuthResult> handleValidationResult(TokenValidationResult validationResult, RouteMatch route) {
        return switch (validationResult) {
            case TokenValidationResult.Valid valid -> {
                // Token is valid, issue an Aussie token for the backend (with group expansion)
                yield issuanceService.issueAsync(valid).map(aussieTokenOpt -> {
                    if (aussieTokenOpt.isPresent()) {
                        LOG.debugv(
                                "Authenticated request for {0}, subject: {1}",
                                route.endpointConfig().path(), valid.subject());
                        return (RouteAuthResult) new RouteAuthResult.Authenticated(aussieTokenOpt.get());
                    } else {
                        // Issuance failed but validation succeeded - still allow with original claims
                        // This is a degraded mode where backends won't get the Aussie token
                        LOG.warnv(
                                "Token issuance failed for {0}, allowing request without Aussie token",
                                route.endpointConfig().path());
                        // Create a minimal token representation for the result
                        final var minimalToken =
                                new AussieToken("", valid.subject(), valid.expiresAt(), valid.claims());
                        return (RouteAuthResult) new RouteAuthResult.Authenticated(minimalToken);
                    }
                });
            }
            case TokenValidationResult.Invalid invalid -> {
                LOG.debugv(
                        "Token validation failed for {0}: {1}",
                        route.endpointConfig().path(), invalid.reason());
                yield Uni.createFrom().item(new RouteAuthResult.Unauthorized(invalid.reason()));
            }
            case TokenValidationResult.NoToken noToken -> {
                LOG.debugv(
                        "No token provided for protected route {0}",
                        route.endpointConfig().path());
                yield Uni.createFrom().item(new RouteAuthResult.Unauthorized("Authentication required"));
            }
        };
    }

    private String extractBearerToken(GatewayRequest request) {
        var authHeaders = request.headers().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            authHeaders = request.headers().get("authorization");
        }
        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }

        String authHeader = authHeaders.get(0);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private Optional<String> extractSessionCookie(GatewayRequest request) {
        if (!sessionConfig.enabled()) {
            return Optional.empty();
        }

        List<String> cookieHeaders = request.headers().get("Cookie");
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            cookieHeaders = request.headers().get("cookie");
        }
        if (cookieHeaders == null || cookieHeaders.isEmpty()) {
            return Optional.empty();
        }

        String cookieName = sessionConfig.cookie().name();

        // Parse cookies from all Cookie headers
        for (String cookieHeader : cookieHeaders) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String trimmed = cookie.trim();
                if (trimmed.startsWith(cookieName + "=")) {
                    String value = trimmed.substring(cookieName.length() + 1);
                    if (!value.isBlank()) {
                        return Optional.of(value);
                    }
                }
            }
        }

        return Optional.empty();
    }
}

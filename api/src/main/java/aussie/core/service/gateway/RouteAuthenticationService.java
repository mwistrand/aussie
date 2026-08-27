package aussie.core.service.gateway;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.InboundCredentials;
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
import aussie.core.util.SafeLogging;

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

        final var credentials = InboundCredentials.from(
                request.headers(),
                sessionConfig.enabled() ? sessionConfig.cookie().name() : null);
        final var bearerToken = credentials.bearerToken();
        final Optional<String> sessionId = credentials.sessionId();
        LOG.debugv(
                "Bearer token present: {0}, Session cookie present: {1}",
                bearerToken.isPresent(), sessionId.isPresent());

        // Check if both auth methods are present - this is not allowed
        if (credentials.hasConflictingCredentials()) {
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
        if (bearerToken.isEmpty()) {
            LOG.debugv(
                    "No authentication provided for protected route {0}",
                    route.endpointConfig().path());
            return Uni.createFrom().item(new RouteAuthResult.Unauthorized("Authentication required"));
        }

        return validationService
                .validate(bearerToken.get())
                .flatMap(validationResult -> handleValidationResult(validationResult, route));
    }

    private Uni<RouteAuthResult> authenticateWithSession(String sessionId, RouteMatch route) {
        return sessionManagement.getSession(sessionId).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugv(
                            "Session hash {0} not found or expired for route {1}",
                            SafeLogging.identifier(sessionId),
                            route.endpointConfig().path());
                }
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

                if (LOG.isDebugEnabled()) {
                    LOG.debugv(
                            "Authenticated session hash {0} for route {1}, subject_hash: {2}",
                            SafeLogging.identifier(sessionId),
                            route.endpointConfig().path(),
                            SafeLogging.identifier(session.userId()));
                }

                // Include session ID for logout tracking (e.g., WebSocket disconnect on logout)
                return new RouteAuthResult.Authenticated(aussieToken, Optional.of(sessionId));
            } catch (SessionTokenService.SessionTokenException e) {
                LOG.errorv(
                        "Failed to generate session token for route {0}, error_type: {1}",
                        route.endpointConfig().path(), SafeLogging.errorType(e));
                return new RouteAuthResult.Unauthorized("Session token generation failed");
            }
        });
    }

    private Uni<RouteAuthResult> handleValidationResult(TokenValidationResult validationResult, RouteMatch route) {
        return switch (validationResult) {
            case TokenValidationResult.Valid valid -> {
                // Token is valid, issue an Aussie token for the backend (with group expansion)
                // Include the route-specific audience if configured
                yield issuanceService
                        .issueAsync(valid, route.audience(), route.service().serviceId())
                        .map(aussieTokenOpt -> {
                            if (aussieTokenOpt.isPresent()
                                    && aussieTokenOpt.get().hasToken()) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debugv(
                                            "Authenticated request for {0}, subject_hash: {1}",
                                            route.endpointConfig().path(), SafeLogging.identifier(valid.subject()));
                                }
                                return (RouteAuthResult) new RouteAuthResult.Authenticated(aussieTokenOpt.get());
                            } else {
                                LOG.warnv(
                                        "Token issuance failed for protected route {0}; denying request",
                                        route.endpointConfig().path());
                                return (RouteAuthResult)
                                        new RouteAuthResult.Unauthorized("Authentication token issuance failed");
                            }
                        });
            }
            case TokenValidationResult.Invalid invalid -> {
                LOG.debugv(
                        "Token validation failed for {0}",
                        route.endpointConfig().path());
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
}

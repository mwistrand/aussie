package aussie.core.service;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.AussieToken;
import aussie.core.model.GatewayRequest;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.TokenValidationResult;

/**
 * Service that handles per-route authentication decisions.
 *
 * <p>Coordinates between token validation and issuance to determine if a request
 * should be allowed to proceed and what token to forward to the backend.
 */
@ApplicationScoped
public class RouteAuthenticationService {

    private static final Logger LOG = Logger.getLogger(RouteAuthenticationService.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenValidationService validationService;
    private final TokenIssuanceService issuanceService;

    @Inject
    public RouteAuthenticationService(TokenValidationService validationService, TokenIssuanceService issuanceService) {
        this.validationService = validationService;
        this.issuanceService = issuanceService;
    }

    /**
     * Authenticate a request for the matched route.
     *
     * @param request the gateway request
     * @param route   the matched route
     * @return authentication result
     */
    public Uni<RouteAuthResult> authenticate(GatewayRequest request, RouteMatch route) {
        boolean authRequired = route.endpoint().authRequired();

        if (!authRequired) {
            LOG.debugv(
                    "Route {0} does not require authentication",
                    route.endpoint().path());
            return Uni.createFrom().item(new RouteAuthResult.NotRequired());
        }

        // Auth is required - extract and validate the token
        String bearerToken = extractBearerToken(request);

        if (bearerToken == null) {
            LOG.debugv(
                    "No bearer token provided for protected route {0}",
                    route.endpoint().path());
            return Uni.createFrom().item(new RouteAuthResult.Unauthorized("Authentication required"));
        }

        return validationService
                .validate(bearerToken)
                .map(validationResult -> handleValidationResult(validationResult, route));
    }

    private RouteAuthResult handleValidationResult(TokenValidationResult validationResult, RouteMatch route) {
        return switch (validationResult) {
            case TokenValidationResult.Valid valid -> {
                // Token is valid, issue an Aussie token for the backend
                Optional<AussieToken> aussieToken = issuanceService.issue(valid);
                if (aussieToken.isPresent()) {
                    LOG.debugv(
                            "Authenticated request for {0}, subject: {1}",
                            route.endpoint().path(), valid.subject());
                    yield new RouteAuthResult.Authenticated(aussieToken.get());
                } else {
                    // Issuance failed but validation succeeded - still allow with original claims
                    // This is a degraded mode where backends won't get the Aussie token
                    LOG.warnv(
                            "Token issuance failed for {0}, allowing request without Aussie token",
                            route.endpoint().path());
                    // Create a minimal token representation for the result
                    AussieToken minimalToken = new AussieToken("", valid.subject(), valid.expiresAt(), valid.claims());
                    yield new RouteAuthResult.Authenticated(minimalToken);
                }
            }
            case TokenValidationResult.Invalid invalid -> {
                LOG.debugv(
                        "Token validation failed for {0}: {1}", route.endpoint().path(), invalid.reason());
                yield new RouteAuthResult.Unauthorized(invalid.reason());
            }
            case TokenValidationResult.NoToken noToken -> {
                LOG.debugv(
                        "No token provided for protected route {0}",
                        route.endpoint().path());
                yield new RouteAuthResult.Unauthorized("Authentication required");
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
}

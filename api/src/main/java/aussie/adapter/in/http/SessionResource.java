package aussie.adapter.in.http;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.logging.Logger;

import aussie.adapter.in.auth.SessionAuthenticationMechanism.SessionPrincipal;
import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.TokenValidationService;
import aussie.core.util.SecureHash;

/**
 * REST endpoints for session management.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    private final SessionManagement sessionManagement;
    private final SessionCookieManager cookieManager;
    private final SessionConfig config;
    private final SecurityIdentity securityIdentity;
    private final TokenValidationService tokenValidationService;

    @Context
    HttpServerRequest request;

    @Inject
    public SessionResource(
            SessionManagement sessionManagement,
            SessionCookieManager cookieManager,
            SessionConfig config,
            SecurityIdentity securityIdentity,
            TokenValidationService tokenValidationService) {
        this.sessionManagement = sessionManagement;
        this.cookieManager = cookieManager;
        this.config = config;
        this.securityIdentity = securityIdentity;
        this.tokenValidationService = tokenValidationService;
    }

    /**
     * Create a new session after successful authentication.
     *
     * <p>This endpoint is called by the auth callback after validating
     * the user's token from their identity provider.
     */
    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> createSession(CreateSessionRequest createRequest) {
        requirePublicCreationEnabled();
        if (createRequest == null
                || createRequest.token() == null
                || createRequest.token().isBlank()) {
            throw GatewayProblem.badRequest("Token is required");
        }

        return createValidatedSession(createRequest.token()).map(session -> {
            LOG.infof("Session created for user: %s", session.userId());

            io.vertx.core.http.Cookie vertxCookie = cookieManager.createCookie(session);
            NewCookie jaxrsCookie = convertToJaxRsCookie(vertxCookie, session);

            // Check for redirect
            String redirectUrl = createRequest.redirectUrl();
            if (redirectUrl != null && !redirectUrl.isBlank()) {
                return Response.seeOther(URI.create(sanitizeRedirectUrl(redirectUrl)))
                        .cookie(jaxrsCookie)
                        .build();
            }

            return Response.ok(Map.of(
                            "sessionId", session.id(),
                            "userId", session.userId(),
                            "expiresAt", session.expiresAt().toString()))
                    .cookie(jaxrsCookie)
                    .build();
        });
    }

    /**
     * Get the current session information.
     */
    @GET
    @Path("/session")
    public Uni<Response> getSession() {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Sessions");
        }

        // Check if user is authenticated via session
        if (securityIdentity.isAnonymous()) {
            throw GatewayProblem.unauthorized("Not authenticated");
        }

        if (!(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            throw GatewayProblem.unauthorized("Not authenticated via session");
        }

        return sessionManagement.getSession(sessionPrincipal.getSessionId()).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                throw GatewayProblem.unauthorized("Session not found");
            }

            Session session = sessionOpt.get();
            return Response.ok(Map.of(
                            "sessionId", session.id(),
                            "userId", session.userId(),
                            "issuer", session.issuer() != null ? session.issuer() : "",
                            "permissions", session.permissions(),
                            "createdAt", session.createdAt().toString(),
                            "expiresAt", session.expiresAt().toString(),
                            "lastAccessedAt", session.lastAccessedAt().toString()))
                    .build();
        });
    }

    /**
     * Invalidate the current session (logout).
     */
    @DELETE
    @Path("/session")
    public Uni<Response> logout() {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Sessions");
        }

        if (securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            // Already logged out
            return Uni.createFrom()
                    .item(Response.ok(Map.of("message", "Logged out")).build());
        }

        return sessionManagement
                .invalidateSession(sessionPrincipal.getSessionId())
                .map(v -> {
                    LOG.infof(
                            "Session invalidated: hash=%s",
                            SecureHash.truncatedSha256(sessionPrincipal.getSessionId(), 8));

                    io.vertx.core.http.Cookie logoutCookie = cookieManager.createLogoutCookie();
                    NewCookie jaxrsCookie = new NewCookie.Builder(logoutCookie.getName())
                            .value("")
                            .path(logoutCookie.getPath())
                            .maxAge(0)
                            .httpOnly(logoutCookie.isHttpOnly())
                            .secure(logoutCookie.isSecure())
                            .build();

                    return Response.ok(Map.of("message", "Logged out"))
                            .cookie(jaxrsCookie)
                            .build();
                });
    }

    /**
     * Invalidate all sessions for the current user (logout everywhere).
     */
    @DELETE
    @Path("/sessions")
    public Uni<Response> logoutAll() {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Sessions");
        }

        if (securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            throw GatewayProblem.unauthorized("Not authenticated");
        }

        return sessionManagement
                .invalidateAllUserSessions(sessionPrincipal.getUserId())
                .map(v -> {
                    LOG.infof("All sessions invalidated for user: %s", sessionPrincipal.getUserId());

                    io.vertx.core.http.Cookie logoutCookie = cookieManager.createLogoutCookie();
                    NewCookie jaxrsCookie = new NewCookie.Builder(logoutCookie.getName())
                            .value("")
                            .path(logoutCookie.getPath())
                            .maxAge(0)
                            .httpOnly(logoutCookie.isHttpOnly())
                            .secure(logoutCookie.isSecure())
                            .build();

                    return Response.ok(Map.of("message", "Logged out from all devices"))
                            .cookie(jaxrsCookie)
                            .build();
                });
    }

    /**
     * Manually refreshes the current session.
     */
    @POST
    @Path("/session/refresh")
    public Uni<Response> refreshSession() {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Sessions");
        }

        if (securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            throw GatewayProblem.unauthorized("Not authenticated");
        }

        return sessionManagement.refreshSession(sessionPrincipal.getSessionId()).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                throw GatewayProblem.unauthorized("Session not found");
            }

            Session session = sessionOpt.get();

            io.vertx.core.http.Cookie vertxCookie = cookieManager.createCookie(session);
            NewCookie jaxrsCookie = convertToJaxRsCookie(vertxCookie, session);

            return Response.ok(Map.of(
                            "sessionId", session.id(),
                            "expiresAt", session.expiresAt().toString(),
                            "lastAccessedAt", session.lastAccessedAt().toString()))
                    .cookie(jaxrsCookie)
                    .build();
        });
    }

    /**
     * Auth callback endpoint - validates token and creates session.
     *
     * <p>This endpoint receives a JWT token from an identity provider callback,
     * validates it, creates a session, and redirects the user to their original page.
     */
    @GET
    @Path("/callback")
    public Uni<Response> authCallback(@QueryParam("token") String token, @QueryParam("redirect") String redirectUrl) {
        requirePublicCreationEnabled();

        if (token == null || token.isBlank()) {
            throw GatewayProblem.badRequest("Token is required");
        }

        // Sanitize redirect URL to prevent open redirect attacks
        String safeRedirectUrl = sanitizeRedirectUrl(redirectUrl);

        return createValidatedSession(token).map(session -> {
            LOG.infof("Session created via callback for user: %s", session.userId());

            io.vertx.core.http.Cookie vertxCookie = cookieManager.createCookie(session);
            NewCookie jaxrsCookie = convertToJaxRsCookie(vertxCookie, session);

            // Redirect to the original page
            return Response.seeOther(URI.create(safeRedirectUrl))
                    .cookie(jaxrsCookie)
                    .build();
        });
    }

    private Uni<Session> createValidatedSession(String token) {
        return tokenValidationService.validate(token).flatMap(result -> {
            if (!(result instanceof TokenValidationResult.Valid valid)) {
                throw GatewayProblem.unauthorized("Invalid token");
            }
            final var userAgent = request.getHeader("User-Agent");
            final var ipAddress =
                    request.remoteAddress() != null ? request.remoteAddress().host() : null;
            return sessionManagement.createSession(valid.identity(), userAgent, ipAddress);
        });
    }

    private void requirePublicCreationEnabled() {
        if (!config.enabled() || !config.publicCreationEnabled()) {
            throw GatewayProblem.featureDisabled("Public session creation");
        }
    }

    /**
     * Sanitizes redirect URL to prevent open redirect attacks.
     * Only allows relative URLs or URLs to known safe origins.
     */
    private String sanitizeRedirectUrl(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isBlank()) {
            return "/";
        }

        // Normalize and validate the URL to prevent bypass attempts
        String normalized = redirectUrl.trim();

        // Reject URLs with protocol-relative patterns or backslashes (which can be
        // normalized to forward slashes by browsers)
        if (normalized.startsWith("//") || normalized.contains("\\") || normalized.contains("%")) {
            LOG.debugf("Rejecting redirect URL with suspicious pattern: %s", redirectUrl);
            return "/";
        }

        // Allow relative URLs (starting with / but not //)
        // Additional check: ensure the path doesn't contain embedded URLs
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            // Verify no protocol in the path (e.g., /http://evil.com)
            if (!normalized.contains("://") && !normalized.contains("@")) {
                return normalized;
            }
            LOG.debugf("Rejecting redirect URL with embedded protocol/credentials: %s", redirectUrl);
            return "/";
        }

        // Allow known safe origins (demo app, localhost variants)
        // In production, this should be configurable
        Set<String> allowedOrigins = Set.of("http://localhost:3000", "http://127.0.0.1:3000");

        try {
            URI uri = URI.create(normalized);
            // Ensure scheme is present (not a protocol-relative URL)
            if (uri.getScheme() == null) {
                LOG.debugf("Rejecting redirect URL without scheme: %s", redirectUrl);
                return "/";
            }
            String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            if (allowedOrigins.contains(origin)) {
                return normalized;
            }
        } catch (Exception e) {
            LOG.debugf("Failed to parse redirect URL: %s", redirectUrl);
        }

        LOG.debugf("Rejecting potentially unsafe redirect URL: %s", redirectUrl);
        return "/";
    }

    private NewCookie convertToJaxRsCookie(io.vertx.core.http.Cookie vertxCookie, Session session) {
        var builder = new NewCookie.Builder(vertxCookie.getName())
                .value(vertxCookie.getValue())
                .path(vertxCookie.getPath())
                .httpOnly(vertxCookie.isHttpOnly())
                .secure(vertxCookie.isSecure());

        if (session.expiresAt() != null) {
            long maxAge = session.expiresAt().getEpochSecond()
                    - java.time.Instant.now().getEpochSecond();
            if (maxAge > 0) {
                builder.maxAge((int) maxAge);
            }
        }

        if (vertxCookie.getDomain() != null) {
            builder.domain(vertxCookie.getDomain());
        }

        return builder.build();
    }

    /**
     * Request body for creating a session.
     */
    public record CreateSessionRequest(String token, String redirectUrl) {}
}

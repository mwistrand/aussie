package aussie.adapter.in.http;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
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
import aussie.config.SessionConfigMapping;
import aussie.core.model.Session;
import aussie.core.port.in.SessionManagement;

/**
 * REST endpoints for session management.
 */
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    private static final Logger LOG = Logger.getLogger(SessionResource.class);

    @Inject
    SessionManagement sessionManagement;

    @Inject
    SessionCookieManager cookieManager;

    @Inject
    SessionConfigMapping config;

    @Inject
    SecurityIdentity securityIdentity;

    @Context
    HttpServerRequest request;

    /**
     * Creates a new session after successful authentication.
     *
     * <p>This endpoint is called by the auth callback after validating
     * the user's token from their identity provider.
     */
    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    public Uni<Response> createSession(CreateSessionRequest createRequest) {
        if (!config.enabled()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
        }

        String userAgent = request.getHeader("User-Agent");
        String ipAddress =
                request.remoteAddress() != null ? request.remoteAddress().host() : null;

        Set<String> permissions =
                createRequest.permissions() != null ? new HashSet<>(createRequest.permissions()) : Set.of();

        Map<String, Object> claims = createRequest.claims() != null ? new HashMap<>(createRequest.claims()) : Map.of();

        return sessionManagement
                .createSession(
                        createRequest.userId(), createRequest.issuer(), claims, permissions, userAgent, ipAddress)
                .map(session -> {
                    LOG.infof("Session created for user: %s", createRequest.userId());

                    io.vertx.core.http.Cookie vertxCookie = cookieManager.createCookie(session);
                    NewCookie jaxrsCookie = convertToJaxRsCookie(vertxCookie, session);

                    // Check for redirect
                    String redirectUrl = createRequest.redirectUrl();
                    if (redirectUrl != null && !redirectUrl.isBlank()) {
                        return Response.seeOther(URI.create(redirectUrl))
                                .cookie(jaxrsCookie)
                                .build();
                    }

                    return Response.ok(Map.of(
                                    "sessionId", session.id(),
                                    "userId", session.userId(),
                                    "expiresAt", session.expiresAt().toString()))
                            .cookie(jaxrsCookie)
                            .build();
                })
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.errorf("Failed to create session: %s", error.getMessage());
                    return Response.serverError()
                            .entity(Map.of("error", "Failed to create session"))
                            .build();
                });
    }

    /**
     * Gets the current session information.
     */
    @GET
    @Path("/session")
    public Uni<Response> getSession() {
        if (!config.enabled()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
        }

        // Check if user is authenticated via session
        if (securityIdentity.isAnonymous()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Not authenticated"))
                            .build());
        }

        if (!(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Not authenticated via session"))
                            .build());
        }

        return sessionManagement.getSession(sessionPrincipal.getSessionId()).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "Session not found"))
                        .build();
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
     * Invalidates the current session (logout).
     */
    @DELETE
    @Path("/session")
    public Uni<Response> logout() {
        if (!config.enabled()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
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
                    LOG.infof("Session invalidated: %s", sessionPrincipal.getSessionId());

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
     * Invalidates all sessions for the current user (logout everywhere).
     */
    @DELETE
    @Path("/sessions")
    public Uni<Response> logoutAll() {
        if (!config.enabled()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
        }

        if (securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Not authenticated"))
                            .build());
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
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
        }

        if (securityIdentity.isAnonymous()
                || !(securityIdentity.getPrincipal() instanceof SessionPrincipal sessionPrincipal)) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.UNAUTHORIZED)
                            .entity(Map.of("error", "Not authenticated"))
                            .build());
        }

        return sessionManagement.refreshSession(sessionPrincipal.getSessionId()).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "Session not found"))
                        .build();
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
     */
    @GET
    @Path("/callback")
    public Uni<Response> authCallback(@QueryParam("token") String token, @QueryParam("redirect") String redirectUrl) {

        if (!config.enabled()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity(Map.of("error", "Sessions are disabled"))
                            .build());
        }

        if (token == null || token.isBlank()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "Token is required"))
                            .build());
        }

        // TODO: Validate token with configured token validator
        // For now, decode JWT claims without validation (demo mode)
        // In production, this should validate the token signature

        // For demo purposes, just acknowledge the callback
        // The actual token validation and session creation should be implemented
        // based on the configured token providers

        return Uni.createFrom()
                .item(Response.status(Response.Status.NOT_IMPLEMENTED)
                        .entity(Map.of(
                                "error", "Token validation not yet implemented",
                                "hint", "Use POST /auth/session with validated claims"))
                        .build());
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
    public record CreateSessionRequest(
            String userId, String issuer, Map<String, Object> claims, Set<String> permissions, String redirectUrl) {}
}

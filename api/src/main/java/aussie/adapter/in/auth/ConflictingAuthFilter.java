package aussie.adapter.in.auth;

import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.config.SessionConfigMapping;

/**
 * Request filter that rejects requests with conflicting authentication.
 *
 * <p>If a request contains both an Authorization header AND a session cookie,
 * it is rejected with 400 Bad Request. This prevents ambiguity in authentication
 * and potential security issues like confused deputy attacks.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100) // Run before authentication
public class ConflictingAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ConflictingAuthFilter.class);

    @Inject
    Instance<SessionConfigMapping> sessionConfigInstance;

    @Inject
    Instance<SessionCookieManager> cookieManagerInstance;

    @Inject
    RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        LOG.infof(
                "ConflictingAuthFilter.filter() called for path: %s",
                requestContext.getUriInfo().getPath());

        // Skip if session config is not available (e.g., in tests without session config)
        if (!sessionConfigInstance.isResolvable()) {
            LOG.info("Session config not resolvable, skipping");
            return;
        }

        SessionConfigMapping sessionConfig = sessionConfigInstance.get();

        // Skip if sessions are disabled
        if (!sessionConfig.enabled()) {
            return;
        }

        // Skip if cookie manager is not available
        if (!cookieManagerInstance.isResolvable()) {
            return;
        }

        boolean hasAuthHeader = requestContext.getHeaderString("Authorization") != null;

        HttpServerRequest request = routingContext.request();
        boolean hasSessionCookie = cookieManagerInstance.get().hasSessionCookie(request);

        if (hasAuthHeader && hasSessionCookie) {
            LOG.warnf("Rejecting request with conflicting authentication: "
                    + "both Authorization header and session cookie present");

            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "conflicting_authentication",
                            "message", "Request cannot contain both Authorization header and session cookie"))
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
    }
}

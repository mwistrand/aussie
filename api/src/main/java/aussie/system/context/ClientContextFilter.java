package aussie.system.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Filter that resolves and stashes the per-request {@link ClientContext}
 * before any other filter that needs the resolved client IP runs.
 *
 * <p>Priority is {@code AUTHENTICATION - 150}, ahead of
 * {@link aussie.system.filter.AuthRateLimitFilter} ({@code AUTHENTICATION - 100})
 * and {@link aussie.system.filter.RateLimitFilter} ({@code AUTHENTICATION - 50}).
 */
@ApplicationScoped
public class ClientContextFilter {

    private final ClientContextResolver resolver;

    @Inject
    public ClientContextFilter(ClientContextResolver resolver) {
        this.resolver = resolver;
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 150)
    public void filter(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        requestContext.setProperty(ClientContextResolver.CONTEXT_PROPERTY, resolver.resolve(vertxRequest));
    }
}

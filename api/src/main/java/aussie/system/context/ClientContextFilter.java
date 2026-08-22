package aussie.system.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;

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
    private final RoutingContext routingContext;

    @Inject
    public ClientContextFilter(ClientContextResolver resolver, RoutingContext routingContext) {
        this.resolver = resolver;
        this.routingContext = routingContext;
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 150)
    public void filter(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        requestContext.setProperty(ClientContextResolver.CONTEXT_PROPERTY, resolver.getOrCompute(routingContext));
    }
}

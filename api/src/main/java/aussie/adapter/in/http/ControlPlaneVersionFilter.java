package aussie.adapter.in.http;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import aussie.adapter.in.rest.ControlPlaneContract;

/** Adds and validates the version marker on gateway-owned control-plane APIs. */
@ApplicationScoped
public class ControlPlaneVersionFilter {

    @RouteFilter(90)
    void filter(RoutingContext context) {
        final var path = context.request().path();
        final var relativePath = path.startsWith("/") ? path.substring(1) : path;
        if (!isControlPlanePath(relativePath)) {
            context.next();
            return;
        }
        final var requestedVersion = context.request().getHeader(ControlPlaneContract.VERSION_HEADER);
        if (requestedVersion != null
                && !requestedVersion.isBlank()
                && !ControlPlaneContract.VERSION.equals(requestedVersion)) {
            context.response()
                    .setStatusCode(406)
                    .putHeader(ControlPlaneContract.VERSION_HEADER, ControlPlaneContract.VERSION)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("supportedVersion", ControlPlaneContract.VERSION)
                            .put("requestedVersion", requestedVersion)
                            .encode());
            return;
        }
        context.response().putHeader(ControlPlaneContract.VERSION_HEADER, ControlPlaneContract.VERSION);
        context.next();
    }

    static boolean isControlPlanePath(String path) {
        return path != null
                && (path.equals("admin")
                        || path.startsWith("admin/")
                        || path.equals("auth")
                        || path.startsWith("auth/"));
    }
}

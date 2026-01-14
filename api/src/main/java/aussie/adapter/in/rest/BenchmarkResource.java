package aussie.adapter.in.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Permission;

/**
 * REST resource for benchmark authorization.
 *
 * <p>
 * This resource provides an authorization check endpoint for the CLI benchmark
 * command. Platform teams with the {@code benchmark.run} permission can run
 * benchmarks; service developers without this permission cannot.
 */
@Path("/admin/benchmark")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class BenchmarkResource {

    /**
     * Check if the authenticated user has permission to run benchmarks.
     *
     * <p>
     * Returns 204 No Content if authorized, or 403 Forbidden if not.
     * The CLI calls this endpoint before starting a benchmark run.
     *
     * @return 204 No Content if authorized
     */
    @GET
    @Path("/authorize")
    @PermissionsAllowed({Permission.BENCHMARK_RUN_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> checkBenchmarkPermission() {
        return Uni.createFrom().item(Response.noContent().build());
    }
}

package aussie.benchmark;

import java.util.concurrent.TimeUnit;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.mockito.Mockito;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import aussie.adapter.in.auth.ApiKeyAuthenticationMechanism;
import aussie.adapter.in.auth.JwtAuthenticationMechanism;
import aussie.core.service.auth.TokenValidationService;
import aussie.system.filter.RouteResolutionFilter;

/**
 * Measures the cost of the PUBLIC short-circuit added to {@link JwtAuthenticationMechanism}
 * and {@link ApiKeyAuthenticationMechanism}: returning early on a single
 * {@link RoutingContext#get(String)} read versus falling through to the existing
 * header-extraction logic.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class PublicAuthBypassBenchmark {

    @State(Scope.Benchmark)
    public static class FixtureState {

        JwtAuthenticationMechanism jwt;
        ApiKeyAuthenticationMechanism apiKey;

        IdentityProviderManager idm;

        RoutingContext publicCtx;
        RoutingContext privateCtx;

        @Setup
        public void setup() {
            final var tokenValidation = Mockito.mock(TokenValidationService.class);
            Mockito.when(tokenValidation.isEnabled()).thenReturn(true);
            jwt = new JwtAuthenticationMechanism(tokenValidation);

            apiKey = new ApiKeyAuthenticationMechanism();

            idm = Mockito.mock(IdentityProviderManager.class);
            Mockito.when(idm.authenticate(Mockito.any(AuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().<SecurityIdentity>nullItem());

            publicCtx = mockContext(true, "Bearer eyJhbGciOiJSUzI1NiJ9.placeholder.placeholder");
            privateCtx = mockContext(false, "Bearer aussie_dummy");
        }

        private static RoutingContext mockContext(boolean isPublic, String authHeader) {
            final var ctx = Mockito.mock(RoutingContext.class);
            final var req = Mockito.mock(HttpServerRequest.class);
            Mockito.when(ctx.request()).thenReturn(req);
            Mockito.when(req.path()).thenReturn("/svc/api/probe");
            Mockito.when(req.getHeader("Authorization")).thenReturn(authHeader);
            if (isPublic) {
                Mockito.when(ctx.get(RouteResolutionFilter.PUBLIC_KEY)).thenReturn(Boolean.TRUE);
            }
            return ctx;
        }
    }

    @Benchmark
    public void publicEndpoint_jwt_withBearer(FixtureState state, Blackhole bh) {
        bh.consume(state.jwt.authenticate(state.publicCtx, state.idm).await().indefinitely());
    }

    @Benchmark
    public void privateEndpoint_jwt_withBearer(FixtureState state, Blackhole bh) {
        bh.consume(state.jwt.authenticate(state.privateCtx, state.idm).await().indefinitely());
    }

    @Benchmark
    public void publicEndpoint_apiKey_withBearer(FixtureState state, Blackhole bh) {
        bh.consume(state.apiKey.authenticate(state.publicCtx, state.idm).await().indefinitely());
    }

    @Benchmark
    public void privateEndpoint_apiKey_withBearer(FixtureState state, Blackhole bh) {
        bh.consume(
                state.apiKey.authenticate(state.privateCtx, state.idm).await().indefinitely());
    }
}

package aussie.benchmark;

import java.util.concurrent.TimeUnit;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
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

import aussie.adapter.in.auth.CredentialAuthenticationMechanism;
import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.core.config.ApiKeyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.TokenValidationService;
import aussie.system.filter.RouteResolutionFilter;

/**
 * Measures the consolidated authentication dispatcher's PUBLIC short-circuit and credential
 * classification paths.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class PublicAuthBypassBenchmark {

    @State(Scope.Benchmark)
    public static class FixtureState {

        CredentialAuthenticationMechanism mechanism;

        IdentityProviderManager idm;

        RoutingContext publicCtx;
        RoutingContext apiKeyCtx;
        RoutingContext jwtCtx;

        @Setup
        public void setup() {
            final var tokenValidation = Mockito.mock(TokenValidationService.class);
            Mockito.when(tokenValidation.isEnabled()).thenReturn(true);
            final var sessionConfig = Mockito.mock(SessionConfig.class);
            final var apiKeyConfig = Mockito.mock(ApiKeyConfig.class);
            mechanism = new CredentialAuthenticationMechanism(
                    tokenValidation,
                    sessionConfig,
                    Mockito.mock(SessionCookieManager.class),
                    Mockito.mock(SessionManagement.class),
                    Mockito.mock(GatewayMetrics.class),
                    Mockito.mock(SecurityMonitor.class),
                    Mockito.mock(ClientContextResolver.class),
                    apiKeyConfig);

            idm = Mockito.mock(IdentityProviderManager.class);
            Mockito.when(idm.authenticate(Mockito.any(AuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().<SecurityIdentity>nullItem());

            publicCtx = mockContext(true, "Bearer eyJhbGciOiJSUzI1NiJ9.placeholder.placeholder");
            apiKeyCtx = mockContext(false, "Bearer aussie_v1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            jwtCtx = mockContext(false, "Bearer eyJhbGciOiJSUzI1NiJ9.placeholder.placeholder");
        }

        private static RoutingContext mockContext(boolean isPublic, String authHeader) {
            final var ctx = Mockito.mock(RoutingContext.class);
            final var req = Mockito.mock(HttpServerRequest.class);
            final var headers = MultiMap.caseInsensitiveMultiMap().add("Authorization", authHeader);
            Mockito.when(ctx.request()).thenReturn(req);
            Mockito.when(req.path()).thenReturn("/svc/api/probe");
            Mockito.when(req.headers()).thenReturn(headers);
            if (isPublic) {
                Mockito.when(ctx.get(RouteResolutionFilter.PUBLIC_KEY)).thenReturn(Boolean.TRUE);
            }
            return ctx;
        }
    }

    @Benchmark
    public void publicEndpoint_withBearer(FixtureState state, Blackhole bh) {
        bh.consume(
                state.mechanism.authenticate(state.publicCtx, state.idm).await().indefinitely());
    }

    @Benchmark
    public void privateEndpoint_withApiKey(FixtureState state, Blackhole bh) {
        bh.consume(
                state.mechanism.authenticate(state.apiKeyCtx, state.idm).await().indefinitely());
    }

    @Benchmark
    public void privateEndpoint_withJwt(FixtureState state, Blackhole bh) {
        bh.consume(state.mechanism.authenticate(state.jwtCtx, state.idm).await().indefinitely());
    }
}

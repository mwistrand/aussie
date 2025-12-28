package aussie.mock;

import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;

import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.spi.OidcTokenExchangeProvider;

/**
 * Mock OIDC token exchange provider for tests.
 *
 * <p>Returns successful token responses without making HTTP calls.
 */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockOidcTokenExchangeProvider implements OidcTokenExchangeProvider {

    private static final String NAME = "mock";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE; // Highest priority to override default
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        return Optional.of(HealthCheckResponse.named("oidc-token-exchange-mock")
                .up()
                .withData("provider", NAME)
                .build());
    }

    @Override
    public Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request) {
        // Return a mock successful response
        OidcTokenExchangeResponse response = new OidcTokenExchangeResponse(
                "mock-access-token-" + System.currentTimeMillis(),
                Optional.empty(), // No ID token
                Optional.empty(), // No refresh token
                "Bearer",
                3600L,
                Optional.of("openid profile email"),
                Map.of());

        return Uni.createFrom().item(response);
    }
}

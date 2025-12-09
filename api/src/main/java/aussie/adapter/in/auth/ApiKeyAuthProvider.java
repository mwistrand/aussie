package aussie.adapter.in.auth;

import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;

import aussie.core.model.AuthenticationContext;
import aussie.core.model.AuthenticationResult;
import aussie.core.model.Principal;
import aussie.core.port.in.ApiKeyManagement;
import aussie.spi.AuthenticationProvider;

/**
 * Authentication provider that validates API keys.
 *
 * <p>Expects a Bearer token in the Authorization header. The token is validated
 * against API keys stored in the gateway via {@link ApiKeyManagement}.
 *
 * <p>Example:
 * <pre>
 * Authorization: Bearer your-api-key-here
 * </pre>
 */
@ApplicationScoped
public class ApiKeyAuthProvider implements AuthenticationProvider {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyManagement apiKeyService;

    @Inject
    public ApiKeyAuthProvider(ApiKeyManagement apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public String name() {
        return "api-key";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean isAvailable() {
        // Always available - API keys are managed at runtime
        return true;
    }

    /**
     * Authenticate using API key validation.
     *
     * <p>Note: This method blocks on the reactive validation call because the
     * {@link AuthenticationProvider} SPI is synchronous. This is acceptable because
     * this provider is part of the deprecated legacy authentication system. The
     * primary authentication path uses Quarkus Security which is fully reactive.
     */
    @Override
    public AuthenticationResult authenticate(MultivaluedMap<String, String> headers, String path) {
        String authHeader = headers.getFirst("Authorization");

        // No Authorization header - skip to next provider
        if (authHeader == null || authHeader.isBlank()) {
            return AuthenticationResult.Skip.instance();
        }

        // Not a Bearer token - skip to next provider
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            return AuthenticationResult.Skip.instance();
        }

        String key = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (key.isBlank()) {
            return AuthenticationResult.Failure.unauthorized("Empty API key");
        }

        // Validate the key (blocking call for legacy SPI compatibility)
        return apiKeyService
                .validate(key)
                .await()
                .indefinitely()
                .map(apiKey -> {
                    var context = AuthenticationContext.builder(Principal.service(apiKey.id(), apiKey.name()))
                            .permissions(apiKey.permissions())
                            .claims(Map.of("keyId", apiKey.id()))
                            .authenticatedAt(Instant.now())
                            .expiresAt(apiKey.expiresAt())
                            .build();

                    return (AuthenticationResult) new AuthenticationResult.Success(context);
                })
                .orElse(AuthenticationResult.Failure.unauthorized("Invalid API key"));
    }
}

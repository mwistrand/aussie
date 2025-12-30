package aussie.spi;

import java.util.Map;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;

import aussie.core.model.auth.TranslatedClaims;

/**
 * SPI for token translation implementations.
 *
 * <p>Platform teams can implement this interface to customize how external IdP
 * token claims are translated to Aussie's internal authorization model. This
 * allows mapping custom claim structures (roles in scopes, groups in custom
 * claims, etc.) to standard Aussie roles and permissions.
 *
 * <p>Built-in providers:
 * <ul>
 *   <li>default (priority: 100) - Extracts from standard "roles" and "permissions" claims</li>
 * </ul>
 *
 * <p>Provider selection order:
 * <ol>
 *   <li>Configured provider (aussie.auth.token-translation.provider)</li>
 *   <li>Highest priority available provider</li>
 * </ol>
 *
 * <h2>Custom Implementation Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class KeycloakTokenTranslator implements TokenTranslatorProvider {
 *
 *     @Override
 *     public String name() {
 *         return "keycloak";
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 150; // Higher than default
 *     }
 *
 *     @Override
 *     public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
 *         // Extract roles from realm_access.roles claim
 *         var realmAccess = (Map<String, Object>) claims.get("realm_access");
 *         var roles = realmAccess != null ? (List<String>) realmAccess.get("roles") : List.of();
 *
 *         return Uni.createFrom().item(new TranslatedClaims(
 *             Set.copyOf(roles),
 *             Set.of(),
 *             Map.of()
 *         ));
 *     }
 * }
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <pre>
 * # Enable token translation
 * aussie.auth.token-translation.enabled=true
 *
 * # Select provider by name
 * aussie.auth.token-translation.provider=keycloak
 * </pre>
 *
 * @see TranslatedClaims
 */
public interface TokenTranslatorProvider {

    /**
     * Return the provider name for configuration selection.
     *
     * <p>This name is used in the configuration property
     * {@code aussie.auth.token-translation.provider} to select this provider.
     *
     * @return Provider name (e.g., "default", "keycloak", "auth0")
     */
    String name();

    /**
     * Return the provider priority for automatic selection.
     *
     * <p>Higher priority providers are preferred when multiple providers
     * are available. Built-in priorities:
     * <ul>
     *   <li>default: 100</li>
     * </ul>
     *
     * <p>Custom implementations should use priorities above 100 to be
     * preferred over the default provider.
     *
     * @return Priority value (higher = more preferred)
     */
    default int priority() {
        return 0;
    }

    /**
     * Check if this provider is available and ready to use.
     *
     * <p>Custom providers may check external dependencies or configuration.
     *
     * <p>This method may be called multiple times and should return
     * quickly. Consider caching the availability state.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create a health indicator for this provider.
     *
     * <p>The health indicator is used for the /q/health endpoint
     * to report the status of token translation capability.
     *
     * @return Health check response, or empty if not supported
     */
    default Optional<HealthCheckResponse> healthCheck() {
        return Optional.empty();
    }

    /**
     * Translate external IdP claims to Aussie's authorization model.
     *
     * <p>Implementations should extract roles and permissions from the provided
     * claims according to the IdP's token structure. The translation may involve:
     * <ul>
     *   <li>Extracting roles from custom claim paths (e.g., "realm_access.roles")</li>
     *   <li>Parsing space-delimited scopes into permissions</li>
     *   <li>Mapping group GUIDs to role names</li>
     *   <li>Applying prefix stripping or other transformations</li>
     * </ul>
     *
     * <p>The returned {@link TranslatedClaims} contains:
     * <ul>
     *   <li>roles - To be expanded via RoleManagement</li>
     *   <li>permissions - Direct permissions to add to the security context</li>
     *   <li>attributes - Additional metadata for downstream use</li>
     * </ul>
     *
     * @param issuer The token issuer (iss claim)
     * @param subject The token subject (sub claim)
     * @param claims All claims from the validated token
     * @return Translated roles and permissions
     */
    Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims);
}

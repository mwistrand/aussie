package aussie.adapter.out.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.model.auth.TranslatedClaims;
import aussie.spi.TokenTranslatorProvider;

/**
 * Default token translator that extracts from standard "roles" and "permissions" claims.
 *
 * <p>This provider preserves the existing behavior where:
 * <ul>
 *   <li>Roles are extracted from the "roles" claim (expected as a list)</li>
 *   <li>Permissions are extracted from the "permissions" claim (expected as a list)</li>
 * </ul>
 *
 * <p>This is the default provider with priority 100. Custom providers should use
 * higher priorities to override this behavior.
 */
@ApplicationScoped
public class DefaultTokenTranslatorProvider implements TokenTranslatorProvider {

    private static final Logger LOG = Logger.getLogger(DefaultTokenTranslatorProvider.class);
    private static final String NAME = "default";
    private static final int PRIORITY = 100;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        return Optional.of(HealthCheckResponse.named("token-translator-default")
                .up()
                .withData("provider", NAME)
                .withData("claimSources", "roles, permissions")
                .build());
    }

    @Override
    public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
        final var roles = extractStringSet(claims, "roles");
        final var permissions = extractStringSet(claims, "permissions");

        LOG.debugf(
                "Token translation: issuer=%s, subject=%s, roles=%s, permissions=%s",
                issuer, subject, roles, permissions);

        return Uni.createFrom().item(new TranslatedClaims(roles, permissions, Map.of()));
    }

    /**
     * Extracts a set of strings from a claim that is expected to be a list.
     *
     * @param claims The claims map
     * @param claimName The name of the claim to extract
     * @return Set of strings from the claim, or empty set if not present or not a list
     */
    private Set<String> extractStringSet(Map<String, Object> claims, String claimName) {
        final var value = claims.get(claimName);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toSet());
        }
        return Set.of();
    }
}

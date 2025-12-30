package aussie.core.model.auth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Result of translating external IdP token claims to Aussie's internal authorization model.
 *
 * <p>This record contains the roles, permissions, and additional attributes extracted
 * from an external identity provider's token after translation rules have been applied.
 *
 * @param roles Set of role identifiers that can be expanded to permissions via RoleManagement
 * @param permissions Set of direct permission strings (e.g., "service.config.read")
 * @param attributes Additional attributes extracted during translation for downstream use
 */
public record TranslatedClaims(Set<String> roles, Set<String> permissions, Map<String, Object> attributes) {

    public TranslatedClaims {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Creates an empty TranslatedClaims instance with no roles, permissions, or attributes.
     *
     * @return empty TranslatedClaims
     */
    public static TranslatedClaims empty() {
        return new TranslatedClaims(Set.of(), Set.of(), Map.of());
    }

    /**
     * Merges this TranslatedClaims with another, combining roles, permissions, and attributes.
     *
     * <p>Attributes from the other instance will overwrite attributes in this instance
     * if they share the same key.
     *
     * @param other the TranslatedClaims to merge with
     * @return a new TranslatedClaims containing the merged values
     */
    public TranslatedClaims merge(TranslatedClaims other) {
        if (other == null) {
            return this;
        }

        final var mergedRoles = new HashSet<>(roles);
        mergedRoles.addAll(other.roles());

        final var mergedPermissions = new HashSet<>(permissions);
        mergedPermissions.addAll(other.permissions());

        final var mergedAttributes = new HashMap<>(attributes);
        mergedAttributes.putAll(other.attributes());

        return new TranslatedClaims(mergedRoles, mergedPermissions, mergedAttributes);
    }

    /**
     * Checks if this TranslatedClaims has any roles.
     *
     * @return true if roles is not empty
     */
    public boolean hasRoles() {
        return !roles.isEmpty();
    }

    /**
     * Checks if this TranslatedClaims has any permissions.
     *
     * @return true if permissions is not empty
     */
    public boolean hasPermissions() {
        return !permissions.isEmpty();
    }

    /**
     * Checks if this TranslatedClaims is empty (no roles, permissions, or attributes).
     *
     * @return true if all collections are empty
     */
    public boolean isEmpty() {
        return roles.isEmpty() && permissions.isEmpty() && attributes.isEmpty();
    }
}

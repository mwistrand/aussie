package aussie.core.model.auth;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Schema for JSON/YAML-based token translation configuration.
 *
 * <p>This configuration defines how to extract claims from external IdP tokens
 * and map them to Aussie's internal authorization model.
 *
 * <h2>Example Configuration</h2>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "sources": [
 *     { "name": "groups", "claim": "groups", "type": "array" },
 *     { "name": "scopes", "claim": "scope", "type": "space-delimited" }
 *   ],
 *   "transforms": [
 *     { "source": "groups", "operations": [{ "type": "strip-prefix", "value": "APP_" }] }
 *   ],
 *   "mappings": {
 *     "roleToPermissions": { "admin": ["service.config.*", "apikeys.*"] },
 *     "directPermissions": { "billing:read": "service.permissions.read" }
 *   },
 *   "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
 * }
 * }</pre>
 *
 * @param version schema version for forward compatibility
 * @param sources claim extraction configurations
 * @param transforms transformations to apply to extracted values
 * @param mappings how extracted values map to roles/permissions
 * @param defaults default behavior settings
 */
public record TranslationConfigSchema(
        int version, List<ClaimSource> sources, List<Transform> transforms, Mappings mappings, Defaults defaults) {

    /**
     * Creates an empty configuration with defaults.
     */
    public static TranslationConfigSchema empty() {
        return new TranslationConfigSchema(
                1, List.of(), List.of(), new Mappings(Map.of(), Map.of()), new Defaults(true, false));
    }

    /**
     * Configuration for extracting values from a claim.
     *
     * @param name identifier for this source (used in transforms)
     * @param claim claim path (supports dot notation, e.g., "realm_access.roles")
     * @param type how to parse the claim value
     */
    public record ClaimSource(String name, String claim, ClaimType type) {

        /**
         * Types of claim value parsing.
         */
        public enum ClaimType {
            /** Claim is a JSON array */
            @JsonProperty("array")
            ARRAY,

            /** Claim is a space-delimited string (e.g., OAuth scopes) */
            @JsonProperty("space-delimited")
            SPACE_DELIMITED,

            /** Claim is a comma-delimited string */
            @JsonProperty("comma-delimited")
            COMMA_DELIMITED,

            /** Claim is a single value */
            @JsonProperty("single")
            SINGLE
        }
    }

    /**
     * Transform configuration for a source.
     *
     * @param source name of the source to transform
     * @param operations list of operations to apply in order
     */
    public record Transform(String source, List<Operation> operations) {}

    /**
     * Transform operation to apply to extracted values.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Operation.StripPrefix.class, name = "strip-prefix"),
        @JsonSubTypes.Type(value = Operation.Replace.class, name = "replace"),
        @JsonSubTypes.Type(value = Operation.Lowercase.class, name = "lowercase"),
        @JsonSubTypes.Type(value = Operation.Uppercase.class, name = "uppercase"),
        @JsonSubTypes.Type(value = Operation.Regex.class, name = "regex")
    })
    public sealed interface Operation
            permits Operation.StripPrefix,
                    Operation.Replace,
                    Operation.Lowercase,
                    Operation.Uppercase,
                    Operation.Regex {

        /**
         * Removes a prefix from each value.
         *
         * @param value prefix to strip
         */
        record StripPrefix(String value) implements Operation {}

        /**
         * Replaces occurrences of a string.
         *
         * @param from string to find
         * @param to replacement string
         */
        record Replace(String from, String to) implements Operation {}

        /**
         * Converts values to lowercase.
         */
        record Lowercase() implements Operation {}

        /**
         * Converts values to uppercase.
         */
        record Uppercase() implements Operation {}

        /**
         * Applies a regex replacement.
         *
         * @param pattern regex pattern to match
         * @param replacement replacement string (supports capture groups)
         */
        record Regex(String pattern, String replacement) implements Operation {}
    }

    /**
     * Mapping configuration for roles and permissions.
     *
     * @param roleToPermissions maps role names to lists of permissions
     * @param directPermissions maps claim values directly to permissions
     */
    public record Mappings(Map<String, List<String>> roleToPermissions, Map<String, String> directPermissions) {

        /**
         * Creates empty mappings.
         */
        public static Mappings empty() {
            return new Mappings(Map.of(), Map.of());
        }
    }

    /**
     * Default behavior settings.
     *
     * @param denyIfNoMatch if true, deny access when no mappings match
     * @param includeUnmapped if true, include unmapped values as-is
     */
    public record Defaults(boolean denyIfNoMatch, boolean includeUnmapped) {

        /**
         * Creates defaults with deny-if-no-match enabled.
         */
        public static Defaults secure() {
            return new Defaults(true, false);
        }
    }
}

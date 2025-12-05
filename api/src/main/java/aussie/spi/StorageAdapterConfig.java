package aussie.spi;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration access for storage providers.
 *
 * <p>Provides type-safe access to application properties.
 * Providers use this to read their configuration without coupling
 * to a specific configuration framework.
 */
public interface StorageAdapterConfig {

    /**
     * Get a required configuration value.
     *
     * @param key The configuration key
     * @return The configuration value
     * @throws IllegalStateException if not configured
     */
    String getRequired(String key);

    /**
     * Get an optional configuration value.
     *
     * @param key The configuration key
     * @return Optional containing the value if present
     */
    Optional<String> get(String key);

    /**
     * Get configuration value with default.
     *
     * @param key The configuration key
     * @param defaultValue The default value if not configured
     * @return The configuration value or default
     */
    String getOrDefault(String key, String defaultValue);

    /**
     * Get all configuration properties with given prefix.
     *
     * <p>Useful for providers with dynamic/nested configuration.
     *
     * @param prefix The configuration key prefix
     * @return Map of matching configuration key-value pairs
     */
    Map<String, String> getWithPrefix(String prefix);

    /**
     * Get integer configuration value.
     *
     * @param key The configuration key
     * @return Optional containing the integer value if present and valid
     */
    Optional<Integer> getInt(String key);

    /**
     * Get long configuration value.
     *
     * @param key The configuration key
     * @return Optional containing the long value if present and valid
     */
    Optional<Long> getLong(String key);

    /**
     * Get boolean configuration value.
     *
     * @param key The configuration key
     * @return Optional containing the boolean value if present
     */
    Optional<Boolean> getBoolean(String key);

    /**
     * Get duration configuration value (ISO-8601 format, e.g., PT15M).
     *
     * @param key The configuration key
     * @return Optional containing the duration if present and valid
     */
    Optional<Duration> getDuration(String key);
}

package aussie.adapter.out.storage;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.Config;

import aussie.spi.StorageAdapterConfig;

/**
 * MicroProfile Config implementation of StorageAdapterConfig.
 *
 * <p>Provides access to application properties for storage providers.
 */
@ApplicationScoped
public class MicroProfileStorageAdapterConfig implements StorageAdapterConfig {

    private final Config config;

    @Inject
    public MicroProfileStorageAdapterConfig(Config config) {
        this.config = config;
    }

    @Override
    public String getRequired(String key) {
        return config.getOptionalValue(key, String.class)
                .orElseThrow(() -> new IllegalStateException("Required configuration not found: " + key));
    }

    @Override
    public Optional<String> get(String key) {
        return config.getOptionalValue(key, String.class);
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        return config.getOptionalValue(key, String.class).orElse(defaultValue);
    }

    @Override
    public Map<String, String> getWithPrefix(String prefix) {
        Map<String, String> result = new HashMap<>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith(prefix)) {
                config.getOptionalValue(name, String.class).ifPresent(value -> result.put(name, value));
            }
        }
        return result;
    }

    @Override
    public Optional<Integer> getInt(String key) {
        return config.getOptionalValue(key, Integer.class);
    }

    @Override
    public Optional<Long> getLong(String key) {
        return config.getOptionalValue(key, Long.class);
    }

    @Override
    public Optional<Boolean> getBoolean(String key) {
        return config.getOptionalValue(key, Boolean.class);
    }

    @Override
    public Optional<Duration> getDuration(String key) {
        return config.getOptionalValue(key, String.class).map(Duration::parse);
    }
}

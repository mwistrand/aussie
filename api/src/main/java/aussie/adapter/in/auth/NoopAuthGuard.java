package aussie.adapter.in.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Startup guard that prevents dangerous-noop auth mode in production.
 *
 * <p>Observes the application startup event and fails fast if
 * {@code aussie.auth.dangerous-noop=true} is set in production mode.
 */
@ApplicationScoped
public class NoopAuthGuard {

    private static final Logger LOG = Logger.getLogger(NoopAuthGuard.class);

    /**
     * Validates that dangerous-noop is not enabled in production mode.
     *
     * @throws IllegalStateException if dangerous-noop is enabled in production
     */
    void onStart(@Observes StartupEvent event) {
        if (isDangerousNoopEnabled() && currentLaunchMode() == LaunchMode.NORMAL) {
            LOG.error("aussie.auth.dangerous-noop=true is not allowed in production mode");
            throw new IllegalStateException("aussie.auth.dangerous-noop=true is not allowed in production mode. "
                    + "This setting disables all authentication. "
                    + "Remove this setting or run in dev/test mode.");
        }
    }

    /**
     * Reads the config dynamically at runtime.
     * This ensures test profiles can override the value.
     */
    boolean isDangerousNoopEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.dangerous-noop", Boolean.class)
                .orElse(false);
    }

    /** Returns the current Quarkus launch mode. */
    LaunchMode currentLaunchMode() {
        return LaunchMode.current();
    }
}

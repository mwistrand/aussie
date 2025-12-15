package aussie.adapter.in.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import aussie.core.model.auth.AuthenticationContext;
import aussie.core.model.auth.AuthenticationResult;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.Principal;
import aussie.spi.AuthenticationProvider;

/**
 * Development-only authentication provider that allows all requests.
 *
 * <p>
 * This provider grants all permissions to all requests without requiring
 * any credentials. It is intended ONLY for local development and testing.
 *
 * <p>
 * <b>WARNING:</b> Never enable this in production! The property
 * {@code aussie.auth.dangerous-noop} must be explicitly set to {@code true}
 * for this provider to be active.
 */
@ApplicationScoped
public class NoopAuthProvider implements AuthenticationProvider {

    private static final Logger LOG = Logger.getLogger(NoopAuthProvider.class);

    private final AtomicBoolean warningLogged = new AtomicBoolean(false);

    /**
     * Reads the config dynamically at runtime.
     * This ensures test profiles can override the value.
     */
    private boolean isDangerousNoopEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.dangerous-noop", Boolean.class)
                .orElse(false);
    }

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public int priority() {
        // Lowest priority - only used when no other provider handles the request
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isAvailable() {
        boolean enabled = isDangerousNoopEnabled();
        if (enabled && warningLogged.compareAndSet(false, true)) {
            LOG.warn("⚠️  DANGEROUS: Authentication is DISABLED (aussie.auth.dangerous-noop=true)");
            LOG.warn("⚠️  All requests will be allowed without authentication!");
            LOG.warn("⚠️  Do NOT use this setting in production!");
        }
        return enabled;
    }

    @Override
    public AuthenticationResult authenticate(MultivaluedMap<String, String> headers, String path) {
        if (!isDangerousNoopEnabled()) {
            return AuthenticationResult.Skip.instance();
        }

        // Grant all permissions
        var context = AuthenticationContext.builder(Principal.system("Development Mode"))
                .permissions(Set.of(Permission.ALL))
                .claims(Map.of("mode", "dangerous-noop"))
                .authenticatedAt(Instant.now())
                .expiresAt(null)
                .build();

        return new AuthenticationResult.Success(context);
    }
}

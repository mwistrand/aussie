package aussie.e2e.support;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton holding state produced by suite bootstrap (gateway URL, demo URL,
 * the bootstrap admin key) and consumed by individual test classes.
 *
 * <p>Populated by {@link SuiteBootstrapListener} once per JUnit launcher
 * session; read-only thereafter.
 */
public final class SuiteContext {

    private static final AtomicReference<SuiteContext> INSTANCE = new AtomicReference<>();

    private final URI gatewayBaseUri;
    private final URI demoBaseUri;
    private final String bootstrapKey;
    private final String demoServiceId;
    private final String cassandraHost;
    private final int cassandraPort;

    private SuiteContext(
            URI gatewayBaseUri,
            URI demoBaseUri,
            String bootstrapKey,
            String demoServiceId,
            String cassandraHost,
            int cassandraPort) {
        this.gatewayBaseUri = gatewayBaseUri;
        this.demoBaseUri = demoBaseUri;
        this.bootstrapKey = bootstrapKey;
        this.demoServiceId = demoServiceId;
        this.cassandraHost = cassandraHost;
        this.cassandraPort = cassandraPort;
    }

    public static SuiteContext install(
            URI gatewayBaseUri,
            URI demoBaseUri,
            String bootstrapKey,
            String demoServiceId,
            String cassandraHost,
            int cassandraPort) {
        var ctx = new SuiteContext(
                gatewayBaseUri, demoBaseUri, bootstrapKey, demoServiceId, cassandraHost, cassandraPort);
        if (!INSTANCE.compareAndSet(null, ctx)) {
            throw new IllegalStateException("SuiteContext already installed");
        }
        return ctx;
    }

    public static SuiteContext get() {
        var ctx = INSTANCE.get();
        if (ctx == null) {
            throw new IllegalStateException("SuiteContext not installed - is the LauncherSession listener registered?");
        }
        return ctx;
    }

    static void clear() {
        INSTANCE.set(null);
    }

    public URI gatewayBaseUri() {
        return gatewayBaseUri;
    }

    public URI demoBaseUri() {
        return demoBaseUri;
    }

    public String bootstrapKey() {
        return bootstrapKey;
    }

    public String demoServiceId() {
        return demoServiceId;
    }

    public String cassandraHost() {
        return cassandraHost;
    }

    public int cassandraPort() {
        return cassandraPort;
    }
}

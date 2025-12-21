package aussie.core.service.auth;

import java.nio.charset.StandardCharsets;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.jboss.logging.Logger;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.RevocationEvent;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.core.port.out.TokenRevocationRepository;

/**
 * Thread-safe bloom filter for O(1) "definitely not revoked" checks.
 *
 * <p>Uses Guava's BloomFilter with configurable expected insertions and
 * false positive probability. The filter is rebuilt periodically from
 * the remote store to handle instance restarts and ensure consistency.
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>{@link #definitelyNotRevoked} - ~100ns, no network I/O</li>
 *   <li>{@link #userDefinitelyNotRevoked} - ~100ns, no network I/O</li>
 *   <li>False positive rate: configurable (default 0.1%)</li>
 * </ul>
 */
@ApplicationScoped
public class RevocationBloomFilter {

    private static final Logger LOG = Logger.getLogger(RevocationBloomFilter.class);

    private final TokenRevocationConfig config;
    private final TokenRevocationRepository repository;
    private final RevocationEventPublisher eventPublisher;
    private final Vertx vertx;

    private volatile BloomFilter<CharSequence> jtiFilter;
    private volatile BloomFilter<CharSequence> userFilter;
    private volatile boolean initialized = false;
    private final Object writeLock = new Object();

    public RevocationBloomFilter(
            TokenRevocationConfig config,
            TokenRevocationRepository repository,
            RevocationEventPublisher eventPublisher,
            Vertx vertx) {
        this.config = config;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.vertx = vertx;
    }

    @PostConstruct
    void init() {
        if (!config.enabled() || !config.bloomFilter().enabled()) {
            LOG.info("Bloom filter disabled, skipping initialization");
            return;
        }

        // Initialize empty filters immediately for fast startup
        initializeEmptyFilters();

        // Rebuild from remote store in background
        rebuildFilters()
                .subscribe()
                .with(
                        v -> LOG.info("Initial bloom filter rebuild completed"),
                        e -> LOG.warnf(e, "Initial bloom filter rebuild failed, using empty filters"));

        // Schedule periodic rebuilds
        schedulePeriodicRebuild();

        // Subscribe to revocation events from other instances
        subscribeToRevocationEvents();
    }

    private void initializeEmptyFilters() {
        var bloomConfig = config.bloomFilter();
        this.jtiFilter = createFilter(bloomConfig.expectedInsertions(), bloomConfig.falsePositiveProbability());
        this.userFilter = createFilter(bloomConfig.expectedInsertions() / 10, bloomConfig.falsePositiveProbability());
        this.initialized = true;
        LOG.infof(
                "Initialized empty bloom filters (expected: %d, fpp: %.4f)",
                bloomConfig.expectedInsertions(), bloomConfig.falsePositiveProbability());
    }

    private BloomFilter<CharSequence> createFilter(int expectedInsertions, double fpp) {
        return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), expectedInsertions, fpp);
    }

    private void schedulePeriodicRebuild() {
        var interval = config.bloomFilter().rebuildInterval();
        vertx.setPeriodic(interval.toMillis(), id -> rebuildFilters()
                .subscribe()
                .with(
                        v -> LOG.debug("Scheduled bloom filter rebuild completed"),
                        e -> LOG.warnf(e, "Scheduled bloom filter rebuild failed")));
        LOG.infof("Scheduled bloom filter rebuild every %s", interval);
    }

    private void subscribeToRevocationEvents() {
        if (!config.pubsub().enabled()) {
            LOG.info("Pub/sub disabled, bloom filter will rely on periodic rebuilds only");
            return;
        }

        eventPublisher
                .subscribe()
                .subscribe()
                .with(this::handleRevocationEvent, e -> LOG.warnf(e, "Revocation event subscription failed"));
        LOG.info("Subscribed to revocation events for bloom filter updates");
    }

    private void handleRevocationEvent(RevocationEvent event) {
        switch (event) {
            case RevocationEvent.JtiRevoked jtiRevoked -> {
                addRevokedJti(jtiRevoked.jti());
                LOG.debugf("Added JTI to bloom filter from event: %s", jtiRevoked.jti());
            }
            case RevocationEvent.UserRevoked userRevoked -> {
                addRevokedUser(userRevoked.userId());
                LOG.debugf("Added user to bloom filter from event: %s", userRevoked.userId());
            }
        }
    }

    /**
     * Check if a JTI is definitely NOT revoked.
     *
     * <p>This is the primary optimization - if the bloom filter says
     * "definitely not present", we can skip the remote lookup entirely.
     *
     * @param jti the JWT ID to check
     * @return true if definitely not revoked, false if maybe revoked
     */
    public boolean definitelyNotRevoked(String jti) {
        if (!initialized || jtiFilter == null) {
            return false; // Conservative: might be revoked
        }
        return !jtiFilter.mightContain(jti);
    }

    /**
     * Check if a user definitely has NO blanket revocation.
     *
     * @param userId the user ID to check
     * @return true if definitely not revoked, false if maybe revoked
     */
    public boolean userDefinitelyNotRevoked(String userId) {
        if (!initialized || userFilter == null) {
            return false; // Conservative: might be revoked
        }
        return !userFilter.mightContain(userId);
    }

    /**
     * Add a JTI to the bloom filter (called on revocation).
     *
     * @param jti the JWT ID to add
     */
    public void addRevokedJti(String jti) {
        synchronized (writeLock) {
            var filter = jtiFilter;
            if (filter != null) {
                filter.put(jti);
            }
        }
    }

    /**
     * Add a user to the bloom filter (called on user revocation).
     *
     * @param userId the user ID to add
     */
    public void addRevokedUser(String userId) {
        synchronized (writeLock) {
            var filter = userFilter;
            if (filter != null) {
                filter.put(userId);
            }
        }
    }

    /**
     * Rebuild filters from remote store.
     *
     * <p>This is called on startup and periodically to ensure
     * the bloom filter stays in sync with the authoritative store.
     *
     * @return Uni completing when rebuild is done
     */
    public Uni<Void> rebuildFilters() {
        if (!config.enabled() || !config.bloomFilter().enabled()) {
            return Uni.createFrom().voidItem();
        }

        var bloomConfig = config.bloomFilter();

        return repository.streamAllRevokedJtis().collect().asList().flatMap(jtis -> repository
                .streamAllRevokedUsers()
                .collect()
                .asList()
                .map(users -> {
                    var newJtiFilter = createFilter(
                            Math.max(bloomConfig.expectedInsertions(), jtis.size()),
                            bloomConfig.falsePositiveProbability());
                    var newUserFilter = createFilter(
                            Math.max(bloomConfig.expectedInsertions() / 10, users.size()),
                            bloomConfig.falsePositiveProbability());

                    jtis.forEach(newJtiFilter::put);
                    users.forEach(newUserFilter::put);

                    synchronized (writeLock) {
                        this.jtiFilter = newJtiFilter;
                        this.userFilter = newUserFilter;
                        this.initialized = true;
                    }

                    LOG.infof("Rebuilt bloom filters: %d JTIs, %d users", jtis.size(), users.size());
                    return null;
                }));
    }

    /**
     * Check if the bloom filter is enabled and initialized.
     *
     * @return true if ready for use
     */
    public boolean isEnabled() {
        return config.enabled() && config.bloomFilter().enabled() && initialized;
    }
}

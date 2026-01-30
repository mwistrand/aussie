package aussie.adapter.out.telemetry;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import org.jboss.logging.Logger;

/**
 * Custom OTel Sampler that implements hierarchical sampling based on
 * platform, service, and endpoint configuration.
 *
 * <p>
 * This sampler handles root span sampling decisions. Parent-based propagation
 * is handled by wrapping this sampler with {@code Sampler.parentBased()} in
 * the {@link HierarchicalSamplerProvider}.
 *
 * <p>
 * The sampling rate is determined by looking up the service from the
 * request path extracted from span attributes.
 *
 * <p>
 * <b>Non-blocking design:</b> This sampler uses
 * {@link SamplingResolver#resolveByServiceIdNonBlocking} which returns the
 * platform default immediately on cache miss and populates the cache
 * asynchronously. This prevents any blocking in the sampler path.
 *
 * <p>
 * <b>Performance considerations:</b>
 * <ul>
 * <li>Local in-memory cache minimizes storage lookups</li>
 * <li>Non-blocking resolution prevents latency on cache miss</li>
 * <li>Service ID extraction from span attributes is O(1)</li>
 * <li>Random number generation uses ThreadLocalRandom</li>
 * </ul>
 */
public class HierarchicalSampler implements Sampler {

    private static final Logger LOG = Logger.getLogger(HierarchicalSampler.class);

    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_TARGET = AttributeKey.stringKey("http.target");

    private final Sampler fallbackSampler;
    private final double fallbackRate;

    /**
     * Create a new HierarchicalSampler.
     *
     * @param fallbackRate the rate to use when the resolver is not available
     */
    public HierarchicalSampler(double fallbackRate) {
        this.fallbackRate = fallbackRate;
        this.fallbackSampler = Sampler.traceIdRatioBased(fallbackRate);
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {

        // Parent-based propagation is handled by the Sampler.parentBased() wrapper
        // in HierarchicalSamplerProvider. This method only handles root span sampling.

        final var resolverOpt = SamplingResolverHolder.get();
        if (resolverOpt.isEmpty()) {
            // Resolver not yet initialized (early startup), use fallback
            LOG.debugv("SamplingResolver not available, using fallback rate {0}", fallbackRate);
            return fallbackSampler.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        final var resolver = resolverOpt.get();
        if (!resolver.isEnabled()) {
            // Hierarchical sampling disabled, use fallback
            return fallbackSampler.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        // Extract service ID from span name or attributes
        final var serviceId = extractServiceId(name, attributes);

        // Use non-blocking resolve to prevent latency on cache miss
        final var effectiveRate = resolver.resolveByServiceIdNonBlocking(serviceId);

        // Apply probabilistic sampling
        if (ThreadLocalRandom.current().nextDouble() < effectiveRate.rate()) {
            return SamplingResult.recordAndSample();
        }

        return SamplingResult.drop();
    }

    @Override
    public String getDescription() {
        return "HierarchicalSampler{fallback=" + fallbackSampler.getDescription() + "}";
    }

    /**
     * Extract service ID from span name or attributes.
     *
     * <p>
     * The span name or attributes typically contain the HTTP path for server spans.
     * We parse the path to extract the service ID.
     *
     * <p>
     * Path format: {@code /{serviceId}/...} or {@code /gateway/...}
     */
    private String extractServiceId(String spanName, Attributes attributes) {
        // Try to extract from HTTP route attribute first (most reliable)
        final var httpRoute = attributes.get(HTTP_ROUTE);
        if (httpRoute != null) {
            return parseServiceIdFromPath(httpRoute);
        }

        // Fall back to URL path
        final var urlPath = attributes.get(URL_PATH);
        if (urlPath != null) {
            return parseServiceIdFromPath(urlPath);
        }

        // Try HTTP target (older semantic convention)
        final var httpTarget = attributes.get(HTTP_TARGET);
        if (httpTarget != null) {
            // Remove query string if present
            final var queryIndex = httpTarget.indexOf('?');
            final var path = queryIndex > 0 ? httpTarget.substring(0, queryIndex) : httpTarget;
            return parseServiceIdFromPath(path);
        }

        // Last resort: try to parse from span name
        if (spanName != null && spanName.startsWith("/")) {
            return parseServiceIdFromPath(spanName);
        }

        return "unknown";
    }

    /**
     * Parse service ID from path.
     *
     * <p>
     * Path format: {@code /{serviceId}/...} or {@code /gateway/...}
     *
     * @param path the request path
     * @return the service ID, or "unknown" if not parseable
     */
    private String parseServiceIdFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "unknown";
        }

        // Remove leading slash
        final var trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.isEmpty()) {
            return "unknown";
        }

        // Skip /gateway prefix (gateway mode, no specific service)
        if (trimmed.startsWith("gateway/") || trimmed.equals("gateway")) {
            return "unknown";
        }

        // Skip common non-service paths
        if (trimmed.startsWith("q/") // Quarkus dev paths (/q/dev, /q/health, etc.)
                || trimmed.startsWith("admin/")
                || trimmed.startsWith("auth/")) {
            return "unknown";
        }

        // Extract first path segment as service ID
        final var slashIndex = trimmed.indexOf('/');
        return slashIndex > 0 ? trimmed.substring(0, slashIndex) : trimmed;
    }
}

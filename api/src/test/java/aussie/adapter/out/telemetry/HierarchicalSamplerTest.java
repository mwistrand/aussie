package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.sampling.EffectiveSamplingRate;
import aussie.core.model.sampling.EffectiveSamplingRate.SamplingSource;

@DisplayName("HierarchicalSampler")
class HierarchicalSamplerTest {

    private HierarchicalSampler sampler;
    private SamplingResolver mockResolver;

    @BeforeEach
    void setUp() {
        sampler = new HierarchicalSampler(1.0);
        mockResolver = mock(SamplingResolver.class);
    }

    @AfterEach
    void tearDown() {
        SamplingResolverHolder.clear();
    }

    @Nested
    @DisplayName("Parent trace context (handled by provider wrapper)")
    class ParentTraceContext {

        // Note: Parent-based propagation is now handled by Sampler.parentBased() wrapper
        // in HierarchicalSamplerProvider. HierarchicalSampler only handles root span sampling.

        @Test
        @DisplayName("should use fallback when resolver not set (regardless of parent context)")
        void shouldUseFallbackWhenResolverNotSet() {
            // Create a sampled parent context
            var parentSpanContext = SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            var parentSpan = Span.wrap(parentSpanContext);
            var parentContext = Context.root().with(parentSpan);

            // Without resolver, uses fallback sampler (rate 1.0 = always sample)
            var result = sampler.shouldSample(
                    parentContext,
                    "00000000000000000000000000000002",
                    "test-span",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.getDecision());
        }

        @Test
        @DisplayName("should use resolver rate with parent context when resolver is set")
        void shouldUseResolverRateWithParentContext() {
            // Set up resolver that returns 0 rate
            when(mockResolver.isEnabled()).thenReturn(true);
            when(mockResolver.resolveByServiceIdNonBlocking(anyString()))
                    .thenReturn(new EffectiveSamplingRate(0.0, SamplingSource.SERVICE));
            SamplingResolverHolder.set(mockResolver);

            // Create a sampled parent context
            var parentSpanContext = SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    TraceFlags.getSampled(),
                    TraceState.getDefault());
            var parentSpan = Span.wrap(parentSpanContext);
            var parentContext = Context.root().with(parentSpan);

            // HierarchicalSampler now uses resolver rate (drops), parent-based handled by wrapper
            var result = sampler.shouldSample(
                    parentContext,
                    "00000000000000000000000000000002",
                    "/test-service/api/test",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.DROP, result.getDecision());
        }
    }

    @Nested
    @DisplayName("Root spans without resolver")
    class RootSpansWithoutResolver {

        @Test
        @DisplayName("should use fallback sampler when resolver not available")
        void shouldUseFallbackSamplerWhenResolverNotAvailable() {
            // Create sampler with 1.0 fallback rate (always sample)
            var alwaysSampleSampler = new HierarchicalSampler(1.0);

            var result = alwaysSampleSampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.getDecision());
        }

        @Test
        @DisplayName("should use fallback sampler with 0 rate when resolver not available")
        void shouldUseFallbackSamplerWithZeroRateWhenResolverNotAvailable() {
            // Create sampler with 0.0 fallback rate (never sample)
            var neverSampleSampler = new HierarchicalSampler(0.0);

            var result = neverSampleSampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.DROP, result.getDecision());
        }
    }

    @Nested
    @DisplayName("Root spans with resolver")
    class RootSpansWithResolver {

        @BeforeEach
        void setUpResolver() {
            when(mockResolver.isEnabled()).thenReturn(true);
            SamplingResolverHolder.set(mockResolver);
        }

        @Test
        @DisplayName("should always sample when rate is 1.0")
        void shouldAlwaysSampleWhenRateIsOne() {
            when(mockResolver.resolveByServiceIdNonBlocking(anyString()))
                    .thenReturn(new EffectiveSamplingRate(1.0, SamplingSource.PLATFORM));

            var result = sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/test-service/api/test",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.getDecision());
        }

        @Test
        @DisplayName("should never sample when rate is 0.0")
        void shouldNeverSampleWhenRateIsZero() {
            when(mockResolver.resolveByServiceIdNonBlocking(anyString()))
                    .thenReturn(new EffectiveSamplingRate(0.0, SamplingSource.SERVICE));

            var result = sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/test-service/api/test",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.DROP, result.getDecision());
        }

        @Test
        @DisplayName("should use fallback when resolver is disabled")
        void shouldUseFallbackWhenResolverIsDisabled() {
            when(mockResolver.isEnabled()).thenReturn(false);

            // With 1.0 fallback rate
            var result = sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/test-service/api/test",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            assertEquals(SamplingDecision.RECORD_AND_SAMPLE, result.getDecision());
        }
    }

    @Nested
    @DisplayName("Service ID extraction")
    class ServiceIdExtraction {

        @BeforeEach
        void setUpResolver() {
            when(mockResolver.isEnabled()).thenReturn(true);
            when(mockResolver.resolveByServiceIdNonBlocking(anyString()))
                    .thenReturn(new EffectiveSamplingRate(1.0, SamplingSource.PLATFORM));
            SamplingResolverHolder.set(mockResolver);
        }

        @Test
        @DisplayName("should extract service ID from http.route attribute")
        void shouldExtractServiceIdFromHttpRoute() {
            var attributes = Attributes.of(AttributeKey.stringKey("http.route"), "/orders/api/orders/{id}");

            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    attributes,
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("orders");
        }

        @Test
        @DisplayName("should extract service ID from url.path attribute")
        void shouldExtractServiceIdFromUrlPath() {
            var attributes = Attributes.of(AttributeKey.stringKey("url.path"), "/users/api/profile");

            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    attributes,
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("users");
        }

        @Test
        @DisplayName("should extract service ID from http.target attribute")
        void shouldExtractServiceIdFromHttpTarget() {
            var attributes = Attributes.of(AttributeKey.stringKey("http.target"), "/products/api/list?page=1");

            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    attributes,
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("products");
        }

        @Test
        @DisplayName("should extract service ID from span name")
        void shouldExtractServiceIdFromSpanName() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/inventory/api/stock",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("inventory");
        }

        @Test
        @DisplayName("should return unknown for gateway paths")
        void shouldReturnUnknownForGatewayPaths() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/gateway/some/endpoint",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should return unknown for admin paths")
        void shouldReturnUnknownForAdminPaths() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/admin/services",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should return unknown for auth paths")
        void shouldReturnUnknownForAuthPaths() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/auth/login",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should return unknown for quarkus dev paths")
        void shouldReturnUnknownForQuarkusDevPaths() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/q/health",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should handle path with trailing slash")
        void shouldHandlePathWithTrailingSlash() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/my-service/",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("my-service");
        }

        @Test
        @DisplayName("should handle exact gateway path")
        void shouldHandleExactGatewayPath() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/gateway",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should prefer http.route over url.path")
        void shouldPreferHttpRouteOverUrlPath() {
            var attributes = Attributes.builder()
                    .put(AttributeKey.stringKey("http.route"), "/route-service/api")
                    .put(AttributeKey.stringKey("url.path"), "/path-service/api")
                    .build();

            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    attributes,
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("route-service");
        }

        @Test
        @DisplayName("should prefer url.path over http.target")
        void shouldPreferUrlPathOverHttpTarget() {
            var attributes = Attributes.builder()
                    .put(AttributeKey.stringKey("url.path"), "/urlpath-service/api")
                    .put(AttributeKey.stringKey("http.target"), "/target-service/api?query=1")
                    .build();

            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "test-span",
                    SpanKind.SERVER,
                    attributes,
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("urlpath-service");
        }

        @Test
        @DisplayName("should return unknown for empty path")
        void shouldReturnUnknownForEmptyPath() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should return unknown for root path")
        void shouldReturnUnknownForRootPath() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("unknown");
        }

        @Test
        @DisplayName("should return service ID for path without nested segments")
        void shouldReturnServiceIdForPathWithoutNestedSegments() {
            sampler.shouldSample(
                    Context.root(),
                    "00000000000000000000000000000001",
                    "/simple-service",
                    SpanKind.SERVER,
                    Attributes.empty(),
                    List.of());

            verify(mockResolver).resolveByServiceIdNonBlocking("simple-service");
        }
    }

    @Nested
    @DisplayName("Description")
    class Description {

        @Test
        @DisplayName("should include fallback sampler in description")
        void shouldIncludeFallbackSamplerInDescription() {
            var description = sampler.getDescription();

            assertTrue(description.contains("HierarchicalSampler"));
            assertTrue(description.contains("fallback"));
        }
    }
}

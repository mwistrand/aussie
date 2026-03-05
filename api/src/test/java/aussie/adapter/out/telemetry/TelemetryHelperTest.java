package aussie.adapter.out.telemetry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryHelper")
class TelemetryHelperTest {

    private TelemetryConfig config;
    private TelemetryConfig.TracingConfig tracingConfig;
    private TelemetryConfig.AttributesConfig attrsConfig;
    private Span span;

    @BeforeEach
    void setUp() {
        config = mock(TelemetryConfig.class);
        tracingConfig = mock(TelemetryConfig.TracingConfig.class);
        attrsConfig = mock(TelemetryConfig.AttributesConfig.class);
        span = mock(Span.class);

        when(config.enabled()).thenReturn(true);
        when(config.tracing()).thenReturn(tracingConfig);
        when(tracingConfig.enabled()).thenReturn(true);
        when(config.attributes()).thenReturn(attrsConfig);
    }

    private TelemetryHelper createHelper() {
        return new TelemetryHelper(config);
    }

    @Nested
    @DisplayName("when tracing disabled")
    class TracingDisabledTests {

        @Test
        @DisplayName("should not set attributes when master switch disabled")
        void shouldNotSetWhenMasterDisabled() {
            when(config.enabled()).thenReturn(false);
            var helper = createHelper();
            when(attrsConfig.requestSize()).thenReturn(true);
            helper.setRequestSize(span, 100);
            verify(span, never()).setAttribute(SpanAttributes.REQUEST_SIZE, 100L);
        }

        @Test
        @DisplayName("should not set attributes when tracing disabled")
        void shouldNotSetWhenTracingDisabled() {
            when(tracingConfig.enabled()).thenReturn(false);
            var helper = createHelper();
            when(attrsConfig.requestSize()).thenReturn(true);
            helper.setRequestSize(span, 100);
            verify(span, never()).setAttribute(SpanAttributes.REQUEST_SIZE, 100L);
        }
    }

    @Nested
    @DisplayName("request/response sizing")
    class SizingTests {

        @Test
        @DisplayName("should set request size when enabled")
        void shouldSetRequestSize() {
            when(attrsConfig.requestSize()).thenReturn(true);
            createHelper().setRequestSize(span, 1024);
            verify(span).setAttribute(SpanAttributes.REQUEST_SIZE, 1024L);
        }

        @Test
        @DisplayName("should not set request size when attribute disabled")
        void shouldNotSetRequestSizeWhenDisabled() {
            when(attrsConfig.requestSize()).thenReturn(false);
            createHelper().setRequestSize(span, 1024);
            verify(span, never()).setAttribute(SpanAttributes.REQUEST_SIZE, 1024L);
        }

        @Test
        @DisplayName("should set response size when enabled")
        void shouldSetResponseSize() {
            when(attrsConfig.responseSize()).thenReturn(true);
            createHelper().setResponseSize(span, 2048);
            verify(span).setAttribute(SpanAttributes.RESPONSE_SIZE, 2048L);
        }
    }

    @Nested
    @DisplayName("upstream attributes")
    class UpstreamTests {

        @Test
        @DisplayName("should set upstream host when enabled")
        void shouldSetUpstreamHost() {
            when(attrsConfig.upstreamHost()).thenReturn(true);
            createHelper().setUpstreamHost(span, "backend.local");
            verify(span).setAttribute(SpanAttributes.UPSTREAM_HOST, "backend.local");
        }

        @Test
        @DisplayName("should set upstream port when enabled")
        void shouldSetUpstreamPort() {
            when(attrsConfig.upstreamPort()).thenReturn(true);
            createHelper().setUpstreamPort(span, 8080);
            verify(span).setAttribute(SpanAttributes.UPSTREAM_PORT, 8080L);
        }

        @Test
        @DisplayName("should set upstream URI when enabled")
        void shouldSetUpstreamUri() {
            when(attrsConfig.upstreamUri()).thenReturn(true);
            createHelper().setUpstreamUri(span, "/api/v1/data");
            verify(span).setAttribute(SpanAttributes.UPSTREAM_URI, "/api/v1/data");
        }

        @Test
        @DisplayName("should set upstream latency when enabled")
        void shouldSetUpstreamLatency() {
            when(attrsConfig.upstreamLatency()).thenReturn(true);
            createHelper().setUpstreamLatency(span, 150);
            verify(span).setAttribute(SpanAttributes.UPSTREAM_LATENCY_MS, 150L);
        }
    }

    @Nested
    @DisplayName("rate limiting attributes")
    class RateLimitTests {

        @Test
        @DisplayName("should set rate limited when enabled")
        void shouldSetRateLimited() {
            when(attrsConfig.rateLimited()).thenReturn(true);
            createHelper().setRateLimited(span, true);
            verify(span).setAttribute(SpanAttributes.RATE_LIMITED, true);
        }

        @Test
        @DisplayName("should set rate limit remaining when enabled")
        void shouldSetRateLimitRemaining() {
            when(attrsConfig.rateLimitRemaining()).thenReturn(true);
            createHelper().setRateLimitRemaining(span, 42);
            verify(span).setAttribute(SpanAttributes.RATE_LIMIT_REMAINING, 42L);
        }

        @Test
        @DisplayName("should set rate limit type when enabled")
        void shouldSetRateLimitType() {
            when(attrsConfig.rateLimitType()).thenReturn(true);
            createHelper().setRateLimitType(span, "sliding_window");
            verify(span).setAttribute(SpanAttributes.RATE_LIMIT_TYPE, "sliding_window");
        }

        @Test
        @DisplayName("should set rate limit retry after when enabled")
        void shouldSetRateLimitRetryAfter() {
            when(attrsConfig.rateLimitRetryAfter()).thenReturn(true);
            createHelper().setRateLimitRetryAfter(span, 30);
            verify(span).setAttribute(SpanAttributes.RATE_LIMIT_RETRY_AFTER, 30L);
        }
    }

    @Nested
    @DisplayName("auth rate limiting attributes")
    class AuthRateLimitTests {

        @Test
        @DisplayName("should set auth rate limited when enabled")
        void shouldSetAuthRateLimited() {
            when(attrsConfig.authRateLimited()).thenReturn(true);
            createHelper().setAuthRateLimited(span, true);
            verify(span).setAttribute(SpanAttributes.AUTH_RATE_LIMITED, true);
        }

        @Test
        @DisplayName("should set auth lockout key when enabled")
        void shouldSetAuthLockoutKey() {
            when(attrsConfig.authLockoutKey()).thenReturn(true);
            createHelper().setAuthLockoutKey(span, "ip:1.2.3.4");
            verify(span).setAttribute(SpanAttributes.AUTH_LOCKOUT_KEY, "ip:1.2.3.4");
        }

        @Test
        @DisplayName("should set auth lockout retry after when enabled")
        void shouldSetAuthLockoutRetryAfter() {
            when(attrsConfig.authLockoutRetryAfter()).thenReturn(true);
            createHelper().setAuthLockoutRetryAfter(span, 60);
            verify(span).setAttribute(SpanAttributes.AUTH_LOCKOUT_RETRY_AFTER, 60L);
        }
    }
}

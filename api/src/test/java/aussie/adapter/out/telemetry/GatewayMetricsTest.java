package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayResult;

@DisplayName("GatewayMetrics")
class GatewayMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private TelemetryConfig enabledConfig() {
        var metricsConfig = mock(TelemetryConfig.MetricsConfig.class);
        when(metricsConfig.enabled()).thenReturn(true);
        var config = mock(TelemetryConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.metrics()).thenReturn(metricsConfig);
        return config;
    }

    private TelemetryConfig disabledConfig() {
        var metricsConfig = mock(TelemetryConfig.MetricsConfig.class);
        when(metricsConfig.enabled()).thenReturn(false);
        var config = mock(TelemetryConfig.class);
        when(config.enabled()).thenReturn(false);
        when(config.metrics()).thenReturn(metricsConfig);
        return config;
    }

    @Nested
    @DisplayName("DisabledMetrics")
    class DisabledMetrics {

        private GatewayMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new GatewayMetrics(registry, disabledConfig());
            metrics.init();
        }

        @Test
        @DisplayName("isEnabled returns false when config is disabled")
        void isEnabledReturnsFalse() {
            assertFalse(metrics.isEnabled());
        }

        @Test
        @DisplayName("recordRequest is a no-op when disabled")
        void recordRequestNoOp() {
            metrics.recordRequest("my-svc", "GET", 200);
            assertNull(registry.find("aussie.requests.total").counter());
        }

        @Test
        @DisplayName("recordProxyLatency is a no-op when disabled")
        void recordProxyLatencyNoOp() {
            metrics.recordProxyLatency("my-svc", "GET", 200, 100);
            assertNull(registry.find("aussie.proxy.latency").timer());
        }

        @Test
        @DisplayName("recordGatewayResult is a no-op when disabled")
        void recordGatewayResultNoOp() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.Success(200, Map.of(), new byte[0]));
            assertNull(registry.find("aussie.gateway.results").counter());
        }

        @Test
        @DisplayName("recordTraffic is a no-op when disabled")
        void recordTrafficNoOp() {
            metrics.recordTraffic("my-svc", "team-1", 100, 200);
            assertNull(registry.find("aussie.traffic.bytes").counter());
        }

        @Test
        @DisplayName("recordError is a no-op when disabled")
        void recordErrorNoOp() {
            metrics.recordError("my-svc", "upstream_timeout");
            assertNull(registry.find("aussie.errors.total").counter());
        }

        @Test
        @DisplayName("recordAuthFailure is a no-op when disabled")
        void recordAuthFailureNoOp() {
            metrics.recordAuthFailure("invalid_key", "127.0.0.1");
            assertNull(registry.find("aussie.auth.failures.total").counter());
        }

        @Test
        @DisplayName("recordAuthSuccess is a no-op when disabled")
        void recordAuthSuccessNoOp() {
            metrics.recordAuthSuccess("api_key");
            assertNull(registry.find("aussie.auth.success.total").counter());
        }

        @Test
        @DisplayName("recordAccessDenied is a no-op when disabled")
        void recordAccessDeniedNoOp() {
            metrics.recordAccessDenied("my-svc", "ip_blocked");
            assertNull(registry.find("aussie.access.denied.total").counter());
        }

        @Test
        @DisplayName("incrementActiveConnections is a no-op when disabled")
        void incrementActiveConnectionsNoOp() {
            metrics.incrementActiveConnections();
            assertNull(registry.find("aussie.connections.active").gauge());
        }

        @Test
        @DisplayName("decrementActiveConnections is a no-op when disabled")
        void decrementActiveConnectionsNoOp() {
            metrics.decrementActiveConnections();
            assertNull(registry.find("aussie.connections.active").gauge());
        }

        @Test
        @DisplayName("incrementActiveWebSockets is a no-op when disabled")
        void incrementActiveWebSocketsNoOp() {
            metrics.incrementActiveWebSockets();
            assertNull(registry.find("aussie.websockets.active").gauge());
        }

        @Test
        @DisplayName("decrementActiveWebSockets is a no-op when disabled")
        void decrementActiveWebSocketsNoOp() {
            metrics.decrementActiveWebSockets();
            assertNull(registry.find("aussie.websockets.active").gauge());
        }

        @Test
        @DisplayName("recordWebSocketConnect is a no-op when disabled")
        void recordWebSocketConnectNoOp() {
            metrics.recordWebSocketConnect("my-svc");
            assertNull(registry.find("aussie.websocket.connections.total").counter());
        }

        @Test
        @DisplayName("recordWebSocketDisconnect is a no-op when disabled")
        void recordWebSocketDisconnectNoOp() {
            metrics.recordWebSocketDisconnect("my-svc", 1000);
            assertNull(registry.find("aussie.websocket.duration").timer());
        }

        @Test
        @DisplayName("recordWebSocketLimitReached is a no-op when disabled")
        void recordWebSocketLimitReachedNoOp() {
            metrics.recordWebSocketLimitReached();
            assertNull(registry.find("aussie.websocket.limit.reached").counter());
        }

        @Test
        @DisplayName("recordRateLimitCheck is a no-op when disabled")
        void recordRateLimitCheckNoOp() {
            metrics.recordRateLimitCheck("my-svc", true, 50);
            assertNull(registry.find("aussie.ratelimit.checks.total").counter());
        }

        @Test
        @DisplayName("recordRateLimitExceeded is a no-op when disabled")
        void recordRateLimitExceededNoOp() {
            metrics.recordRateLimitExceeded("my-svc", "http");
            assertNull(registry.find("aussie.ratelimit.exceeded.total").counter());
        }

        @Test
        @DisplayName("recordProxyTimeout is a no-op when disabled")
        void recordProxyTimeoutNoOp() {
            metrics.recordProxyTimeout("my-svc", "connect");
            assertNull(registry.find("aussie.proxy.timeouts.total").counter());
        }

        @Test
        @DisplayName("recordProxyConnectionFailure is a no-op when disabled")
        void recordProxyConnectionFailureNoOp() {
            metrics.recordProxyConnectionFailure("my-svc", "connection_refused");
            assertNull(registry.find("aussie.proxy.connection.failures.total").counter());
        }

        @Test
        @DisplayName("recordJwksFetchTimeout is a no-op when disabled")
        void recordJwksFetchTimeoutNoOp() {
            metrics.recordJwksFetchTimeout("auth.example.com");
            assertNull(registry.find("aussie.jwks.fetch.timeouts.total").counter());
        }

        @Test
        @DisplayName("recordCassandraTimeout is a no-op when disabled")
        void recordCassandraTimeoutNoOp() {
            metrics.recordCassandraTimeout("service_repo", "findById");
            assertNull(registry.find("aussie.cassandra.timeouts.total").counter());
        }

        @Test
        @DisplayName("recordRedisTimeout is a no-op when disabled")
        void recordRedisTimeoutNoOp() {
            metrics.recordRedisTimeout("rate_limit_repo", "increment");
            assertNull(registry.find("aussie.redis.timeouts.total").counter());
        }

        @Test
        @DisplayName("recordRedisFailure is a no-op when disabled")
        void recordRedisFailureNoOp() {
            metrics.recordRedisFailure("rate_limit_repo", "increment");
            assertNull(registry.find("aussie.redis.failures.total").counter());
        }
    }

    @Nested
    @DisplayName("EnabledMetrics")
    class EnabledMetrics {

        private GatewayMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new GatewayMetrics(registry, enabledConfig());
            metrics.init();
        }

        @Test
        @DisplayName("isEnabled returns true when config is enabled")
        void isEnabledReturnsTrue() {
            assertTrue(metrics.isEnabled());
        }

        @Test
        @DisplayName("recordRequest increments counter with correct tags")
        void recordRequest() {
            metrics.recordRequest("my-svc", "GET", 200);

            var counter = registry.find("aussie.requests.total")
                    .tag("service_id", "my-svc")
                    .tag("method", "GET")
                    .tag("status", "200")
                    .tag("status_class", "2xx")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRequest uses 'unknown' for null serviceId")
        void recordRequestNullServiceId() {
            metrics.recordRequest(null, "POST", 404);

            var counter = registry.find("aussie.requests.total")
                    .tag("service_id", "unknown")
                    .tag("method", "POST")
                    .tag("status", "404")
                    .tag("status_class", "4xx")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRequest maps status classes correctly")
        void recordRequestStatusClasses() {
            metrics.recordRequest("svc", "GET", 101);
            metrics.recordRequest("svc", "GET", 301);
            metrics.recordRequest("svc", "GET", 500);

            assertNotNull(registry.find("aussie.requests.total")
                    .tag("status_class", "1xx")
                    .counter());
            assertNotNull(registry.find("aussie.requests.total")
                    .tag("status_class", "3xx")
                    .counter());
            assertNotNull(registry.find("aussie.requests.total")
                    .tag("status_class", "5xx")
                    .counter());
        }

        @Test
        @DisplayName("recordProxyLatency records timer with correct tags")
        void recordProxyLatency() {
            metrics.recordProxyLatency("my-svc", "GET", 200, 150);

            var timer = registry.find("aussie.proxy.latency")
                    .tag("service_id", "my-svc")
                    .tag("method", "GET")
                    .tag("status_class", "2xx")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps Success correctly")
        void recordGatewayResultSuccess() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.Success(200, Map.of(), new byte[0]));

            var counter = registry.find("aussie.gateway.results")
                    .tag("service_id", "my-svc")
                    .tag("result_type", "success")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps RouteNotFound correctly")
        void recordGatewayResultRouteNotFound() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.RouteNotFound("/path"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "route_not_found")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps ServiceNotFound correctly")
        void recordGatewayResultServiceNotFound() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.ServiceNotFound("svc"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "service_not_found")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps ReservedPath correctly")
        void recordGatewayResultReservedPath() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.ReservedPath("/admin"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "reserved_path")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps Error correctly")
        void recordGatewayResultError() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.Error("error"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "error")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps Unauthorized correctly")
        void recordGatewayResultUnauthorized() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.Unauthorized("reason"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "unauthorized")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps Forbidden correctly")
        void recordGatewayResultForbidden() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.Forbidden("reason"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "forbidden")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordGatewayResult maps BadRequest correctly")
        void recordGatewayResultBadRequest() {
            metrics.recordGatewayResult("my-svc", new GatewayResult.BadRequest("reason"));

            var counter = registry.find("aussie.gateway.results")
                    .tag("result_type", "bad_request")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordTraffic records inbound and outbound bytes")
        void recordTraffic() {
            metrics.recordTraffic("my-svc", "team-1", 100, 200);

            var inbound = registry.find("aussie.traffic.bytes")
                    .tag("service_id", "my-svc")
                    .tag("team_id", "team-1")
                    .tag("direction", "inbound")
                    .counter();
            assertNotNull(inbound);
            assertEquals(100.0, inbound.count());

            var outbound = registry.find("aussie.traffic.bytes")
                    .tag("service_id", "my-svc")
                    .tag("team_id", "team-1")
                    .tag("direction", "outbound")
                    .counter();
            assertNotNull(outbound);
            assertEquals(200.0, outbound.count());
        }

        @Test
        @DisplayName("recordError increments error counter with correct tags")
        void recordError() {
            metrics.recordError("my-svc", "upstream_timeout");

            final var counter = registry.find("aussie.errors.total")
                    .tag("service_id", "my-svc")
                    .tag("error_type", "upstream_timeout")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordError preserves stable problem codes")
        void recordProblemCode() {
            metrics.recordError("my-svc", "service_not_found");

            final var counter = registry.find("aussie.errors.total")
                    .tag("service_id", "my-svc")
                    .tag("error_type", "service_not_found")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("unrecognized labels share bounded fallback series")
        void unrecognizedLabelsUseOther() {
            for (int i = 0; i < 1_000; i++) {
                metrics.recordError("my-svc", "error-" + i);
                metrics.recordAuthFailure("reason-" + i, null);
                metrics.recordAuthSuccess("method-" + i);
                metrics.recordRequest("my-svc", "method-" + i, 1_000 + i);
                metrics.recordAccessDenied("my-svc", "reason-" + i);
                metrics.recordRateLimitExceeded("my-svc", "limit-" + i);
                metrics.recordRateLimitFallback("my-svc", "mode-" + i);
                metrics.recordProxyTimeout("my-svc", "timeout-" + i);
                metrics.recordProxyConnectionFailure("my-svc", "error-" + i);
            }

            assertEquals(1, registry.find("aussie.requests.total").meters().size());
            assertEquals(1, registry.find("aussie.errors.total").meters().size());
            assertEquals(1, registry.find("aussie.auth.failures.total").meters().size());
            assertEquals(1, registry.find("aussie.auth.success.total").meters().size());
            assertEquals(1, registry.find("aussie.access.denied.total").meters().size());
            assertEquals(
                    1, registry.find("aussie.ratelimit.exceeded.total").meters().size());
            assertEquals(
                    1,
                    registry.find("aussie.ratelimit.fallback.activations")
                            .meters()
                            .size());
            assertEquals(
                    1, registry.find("aussie.proxy.timeouts.total").meters().size());
            assertEquals(
                    1,
                    registry.find("aussie.proxy.connection.failures.total")
                            .meters()
                            .size());
        }

        @Test
        @DisplayName("recordAuthFailure increments failure counter without client labels")
        void recordAuthFailure() {
            metrics.recordAuthFailure("invalid_key", "127.0.0.1");

            var counter = registry.find("aussie.auth.failures.total")
                    .tag("reason", "invalid_key")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("recordAuthFailure does not create a client label for null IP")
        void recordAuthFailureNullIp() {
            metrics.recordAuthFailure("invalid_session", null);

            var counter = registry.find("aussie.auth.failures.total")
                    .tag("reason", "invalid_session")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("recordAuthSuccess increments success counter with method tag")
        void recordAuthSuccess() {
            metrics.recordAuthSuccess("api_key");

            var counter = registry.find("aussie.auth.success.total")
                    .tag("method", "api_key")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordAccessDenied increments counter with correct tags")
        void recordAccessDenied() {
            metrics.recordAccessDenied("my-svc", "ip_blocked");

            var counter = registry.find("aussie.access.denied.total")
                    .tag("service_id", "my-svc")
                    .tag("reason", "ip_blocked")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("incrementActiveConnections increases gauge value")
        void incrementActiveConnections() {
            metrics.incrementActiveConnections();
            metrics.incrementActiveConnections();

            var gauge = registry.find("aussie.connections.active").gauge();
            assertNotNull(gauge);
            assertEquals(2.0, gauge.value());
        }

        @Test
        @DisplayName("decrementActiveConnections decreases gauge value")
        void decrementActiveConnections() {
            metrics.incrementActiveConnections();
            metrics.incrementActiveConnections();
            metrics.decrementActiveConnections();

            var gauge = registry.find("aussie.connections.active").gauge();
            assertNotNull(gauge);
            assertEquals(1.0, gauge.value());
        }

        @Test
        @DisplayName("incrementActiveWebSockets increases gauge value")
        void incrementActiveWebSockets() {
            metrics.incrementActiveWebSockets();

            var gauge = registry.find("aussie.websockets.active").gauge();
            assertNotNull(gauge);
            assertEquals(1.0, gauge.value());
        }

        @Test
        @DisplayName("decrementActiveWebSockets decreases gauge value")
        void decrementActiveWebSockets() {
            metrics.incrementActiveWebSockets();
            metrics.incrementActiveWebSockets();
            metrics.decrementActiveWebSockets();

            var gauge = registry.find("aussie.websockets.active").gauge();
            assertNotNull(gauge);
            assertEquals(1.0, gauge.value());
        }

        @Test
        @DisplayName("getActiveWebSockets returns current count")
        void getActiveWebSockets() {
            metrics.incrementActiveWebSockets();
            metrics.incrementActiveWebSockets();
            assertEquals(2, metrics.getActiveWebSockets());
        }

        @Test
        @DisplayName("recordWebSocketConnect increments connection counter")
        void recordWebSocketConnect() {
            metrics.recordWebSocketConnect("my-svc");

            var counter = registry.find("aussie.websocket.connections.total")
                    .tag("service_id", "my-svc")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordWebSocketDisconnect records duration timer")
        void recordWebSocketDisconnect() {
            metrics.recordWebSocketDisconnect("my-svc", 5000);

            var timer = registry.find("aussie.websocket.duration")
                    .tag("service_id", "my-svc")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("recordWebSocketLimitReached increments limit counter")
        void recordWebSocketLimitReached() {
            metrics.recordWebSocketLimitReached();

            var counter = registry.find("aussie.websocket.limit.reached").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRateLimitCheck increments check counter with allowed tag")
        void recordRateLimitCheck() {
            metrics.recordRateLimitCheck("my-svc", true, 50);

            var counter = registry.find("aussie.ratelimit.checks.total")
                    .tag("service_id", "my-svc")
                    .tag("allowed", "true")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRateLimitCheck records denied check")
        void recordRateLimitCheckDenied() {
            metrics.recordRateLimitCheck("my-svc", false, 0);

            var counter = registry.find("aussie.ratelimit.checks.total")
                    .tag("service_id", "my-svc")
                    .tag("allowed", "false")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRateLimitExceeded increments exceeded counter")
        void recordRateLimitExceeded() {
            metrics.recordRateLimitExceeded("my-svc", "http");

            var counter = registry.find("aussie.ratelimit.exceeded.total")
                    .tag("service_id", "my-svc")
                    .tag("limit_type", "http")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRateLimitFallback preserves configured fallback mode")
        void recordRateLimitFallback() {
            metrics.recordRateLimitFallback("my-svc", "local-bucket");

            var counter = registry.find("aussie.ratelimit.fallback.activations")
                    .tag("service_id", "my-svc")
                    .tag("mode", "local-bucket")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordProxyTimeout increments timeout counter")
        void recordProxyTimeout() {
            metrics.recordProxyTimeout("my-svc", "connect");

            var counter = registry.find("aussie.proxy.timeouts.total")
                    .tag("service_id", "my-svc")
                    .tag("timeout_type", "connect")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordProxyConnectionFailure preserves classified failure type")
        void recordProxyConnectionFailure() {
            metrics.recordProxyConnectionFailure("my-svc", "tls_handshake_failed");

            var counter = registry.find("aussie.proxy.connection.failures.total")
                    .tag("service_id", "my-svc")
                    .tag("error_type", "tls_handshake_failed")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordJwksFetchTimeout increments JWKS timeout counter")
        void recordJwksFetchTimeout() {
            metrics.recordJwksFetchTimeout("auth.example.com");

            var counter = registry.find("aussie.jwks.fetch.timeouts.total")
                    .tag("jwks_uri_host", "auth.example.com")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordCassandraTimeout increments Cassandra timeout counter")
        void recordCassandraTimeout() {
            metrics.recordCassandraTimeout("service_repo", "findById");

            var counter = registry.find("aussie.cassandra.timeouts.total")
                    .tag("repository", "service_repo")
                    .tag("operation", "findById")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRedisTimeout increments Redis timeout counter")
        void recordRedisTimeout() {
            metrics.recordRedisTimeout("rate_limit_repo", "increment");

            var counter = registry.find("aussie.redis.timeouts.total")
                    .tag("repository", "rate_limit_repo")
                    .tag("operation", "increment")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRedisFailure increments Redis failure counter")
        void recordRedisFailure() {
            metrics.recordRedisFailure("rate_limit_repo", "increment");

            var counter = registry.find("aussie.redis.failures.total")
                    .tag("repository", "rate_limit_repo")
                    .tag("operation", "increment")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }
    }
}

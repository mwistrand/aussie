package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.net.SocketAddress;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.gateway.ProxyRequestPreparer;
import aussie.core.service.routing.UpstreamAddressResolver;

@DisplayName("ProxyHttpClient")
@ExtendWith(MockitoExtension.class)
class ProxyHttpClientTest {

    @Mock
    private OutboundHttpClients outboundClient;

    @Mock
    private ProxyRequestPreparer requestPreparer;

    @Mock
    private Tracer tracer;

    @Mock
    private TextMapPropagator propagator;

    @Mock
    private TelemetryHelper telemetryHelper;

    @Mock
    private ResiliencyConfig resiliencyConfig;

    @Mock
    private ResiliencyConfig.HttpConfig httpConfig;

    @Mock
    private Metrics metrics;

    @Mock
    private UpstreamAddressResolver addressResolver;

    @Mock
    private WebClient webClient;

    @Mock
    private SpanBuilder spanBuilder;

    @Mock
    private Span span;

    private ProxyHttpClient proxyHttpClient;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(resiliencyConfig.http()).thenReturn(httpConfig);
        lenient().when(outboundClient.webClient()).thenReturn(webClient);
        lenient().when(httpConfig.connectTimeout()).thenReturn(java.time.Duration.ofSeconds(5));
        lenient().when(httpConfig.requestTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
        lenient()
                .when(addressResolver.resolve(any(URI.class)))
                .thenReturn(
                        Uni.createFrom().item(io.vertx.core.net.SocketAddress.inetSocketAddress(443, "203.0.113.1")));

        proxyHttpClient = new ProxyHttpClient(
                outboundClient,
                requestPreparer,
                tracer,
                propagator,
                telemetryHelper,
                resiliencyConfig,
                metrics,
                addressResolver);
    }

    private void setupSpanBuilder() {
        lenient().when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setSpanKind(any(SpanKind.class))).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.setAttribute(anyString(), anyLong())).thenReturn(spanBuilder);
        lenient().when(spanBuilder.startSpan()).thenReturn(span);
    }

    @SuppressWarnings("unchecked")
    private HttpRequest<Buffer> mockHttpRequest() {
        HttpRequest<Buffer> httpRequest = mock(HttpRequest.class);
        lenient().when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        lenient().when(httpRequest.ssl(anyBoolean())).thenReturn(httpRequest);
        lenient().when(httpRequest.followRedirects(anyBoolean())).thenReturn(httpRequest);
        lenient().when(httpRequest.timeout(anyLong())).thenReturn(httpRequest);
        return httpRequest;
    }

    @Nested
    @DisplayName("forward()")
    class Forward {

        @Test
        @DisplayName("shouldReturnProxyResponseWhenUpstreamSucceeds")
        @SuppressWarnings("unchecked")
        void shouldReturnProxyResponseWhenUpstreamSucceeds() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri =
                    URI.create("https://user:password@backend.example.com:8443/api/test?token=do-not-export#fragment");
            var headers = Map.of("Accept", List.of("application/json"));
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, headers, null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(8443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            // Mock response
            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            var responseBody = Buffer.buffer("OK");
            when(httpResponse.body()).thenReturn(responseBody);
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of("Content-Type"));
            when(multiMap.getAll("Content-Type")).thenReturn(List.of("application/json"));

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));

            var filteredHeaders = Map.of("Content-Type", List.of("application/json"));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(filteredHeaders);

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, result.statusCode());
            assertNotNull(result.headers());
            verify(spanBuilder).setAttribute(SpanAttributes.HTTP_URL, "https://backend.example.com:8443/api/test");
            verify(telemetryHelper).setUpstreamUri(span, "https://backend.example.com:8443/api/test");
            verify(span).end();
        }

        @Test
        @DisplayName("shouldReturn504WhenTimeoutOccurs")
        @SuppressWarnings("unchecked")
        void shouldReturn504WhenTimeoutOccurs() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            when(httpRequest.send()).thenReturn(Uni.createFrom().failure(new TimeoutException("Request timed out")));

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(504, result.statusCode());
            assertArrayEquals("Gateway Timeout".getBytes(), result.body());
            verify(metrics).recordProxyTimeout("backend.example.com", "request");
            verify(span).setStatus(StatusCode.ERROR, "Gateway Timeout");
            verify(span).end();
        }

        @Test
        @DisplayName("shouldIncludeAddressResolutionInRequestTimeout")
        void shouldIncludeAddressResolutionInRequestTimeout() {
            setupSpanBuilder();
            when(httpConfig.requestTimeout()).thenReturn(Duration.ofMillis(10));
            when(addressResolver.resolve(any(URI.class)))
                    .thenReturn(Uni.createFrom().nothing());
            var preparedRequest =
                    new PreparedProxyRequest("GET", URI.create("https://backend.example.com/api/test"), Map.of(), null);

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(1));

            assertEquals(504, result.statusCode());
            verify(metrics).recordProxyTimeout("backend.example.com", "request");
            verifyNoInteractions(webClient);
        }

        @Test
        @DisplayName("shouldSendBodyWhenPresent")
        @SuppressWarnings("unchecked")
        void shouldSendBodyWhenPresent() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/data");
            var body = "{\"key\":\"value\"}".getBytes();
            var preparedRequest = new PreparedProxyRequest("POST", targetUri, Map.of(), body);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(201);
            when(httpResponse.body()).thenReturn(Buffer.buffer("Created"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.sendBuffer(any(Buffer.class)))
                    .thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(201, result.statusCode());
            verify(httpRequest).sendBuffer(any(Buffer.class));
        }

        @Test
        @DisplayName("shouldSetErrorStatusForUpstream4xxResponse")
        @SuppressWarnings("unchecked")
        void shouldSetErrorStatusForUpstream4xxResponse() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(404);
            when(httpResponse.body()).thenReturn(Buffer.buffer("Not Found"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(404, result.statusCode());
            verify(span).setStatus(StatusCode.ERROR, "HTTP 404");
        }

        @Test
        @DisplayName("shouldHandleEmptyResponseBody")
        @SuppressWarnings("unchecked")
        void shouldHandleEmptyResponseBody() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(204);
            when(httpResponse.body()).thenReturn(Buffer.buffer(new byte[0]));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, result.statusCode());
            assertNotNull(result.body());
            assertEquals(0, result.body().length);
        }

        @Test
        @DisplayName("shouldHandleNullResponseBody")
        @SuppressWarnings("unchecked")
        void shouldHandleNullResponseBody() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(204);
            // body() returns null to test the null check in toProxyResponse
            when(httpResponse.body()).thenReturn(null);
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, result.statusCode());
            assertNotNull(result.body());
            assertEquals(0, result.body().length);
        }

        @Test
        @DisplayName("shouldIncludeQueryStringInRequest")
        @SuppressWarnings("unchecked")
        void shouldIncludeQueryStringInRequest() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/search?q=test&page=1");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            eq("/api/search?q=test&page=1")))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(Buffer.buffer("results"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, result.statusCode());
            verify(webClient)
                    .request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            eq("/api/search?q=test&page=1"));
        }

        @Test
        @DisplayName("shouldUseDefaultHttpPortForHttpScheme")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultHttpPortForHttpScheme() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("http://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(80),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(Buffer.buffer("OK"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            var result = proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, result.statusCode());
            verify(webClient)
                    .request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(80),
                            eq("backend.example.com"),
                            anyString());
        }

        @Test
        @DisplayName("shouldNotSetErrorStatusForSuccessfulResponse")
        @SuppressWarnings("unchecked")
        void shouldNotSetErrorStatusForSuccessfulResponse() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/test");
            var preparedRequest = new PreparedProxyRequest("GET", targetUri, Map.of(), null);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(Buffer.buffer("OK"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.send()).thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            verify(span, never()).setStatus(any(StatusCode.class), anyString());
        }

        @Test
        @DisplayName("shouldSetRequestSizeWhenBodyIsPresent")
        @SuppressWarnings("unchecked")
        void shouldSetRequestSizeWhenBodyIsPresent() {
            setupSpanBuilder();
            var httpRequest = mockHttpRequest();

            var targetUri = URI.create("https://backend.example.com/api/data");
            var body = "test body content".getBytes();
            var preparedRequest = new PreparedProxyRequest("POST", targetUri, Map.of(), body);

            when(webClient.request(
                            any(HttpMethod.class),
                            any(SocketAddress.class),
                            eq(443),
                            eq("backend.example.com"),
                            anyString()))
                    .thenReturn(httpRequest);

            HttpResponse<Buffer> httpResponse = mock(HttpResponse.class);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(Buffer.buffer("OK"));
            var multiMap = mock(MultiMap.class);
            when(httpResponse.headers()).thenReturn(multiMap);
            when(multiMap.names()).thenReturn(java.util.Set.of());

            when(httpRequest.sendBuffer(any(Buffer.class)))
                    .thenReturn(Uni.createFrom().item(httpResponse));
            when(requestPreparer.filterResponseHeaders(any())).thenReturn(Map.of());

            proxyHttpClient.forward(preparedRequest).await().atMost(Duration.ofSeconds(5));

            verify(telemetryHelper).setRequestSize(span, body.length);
        }
    }

    @Nested
    @DisplayName("isTimeoutException()")
    class IsTimeoutException {

        private boolean invokeIsTimeoutException(Throwable error) throws Exception {
            var method = ProxyHttpClient.class.getDeclaredMethod("isTimeoutException", Throwable.class);
            method.setAccessible(true);
            return (boolean) method.invoke(proxyHttpClient, error);
        }

        @Test
        @DisplayName("shouldReturnTrueForDirectTimeoutException")
        void shouldReturnTrueForDirectTimeoutException() throws Exception {
            assertTrue(invokeIsTimeoutException(new TimeoutException("timed out")));
        }

        @Test
        @DisplayName("shouldReturnTrueForNestedTimeoutException")
        void shouldReturnTrueForNestedTimeoutException() throws Exception {
            var cause = new TimeoutException("inner timeout");
            var wrapper = new RuntimeException("wrapper", cause);
            assertTrue(invokeIsTimeoutException(wrapper));
        }

        @Test
        @DisplayName("shouldReturnFalseWhenNoTimeoutInChain")
        void shouldReturnFalseWhenNoTimeoutInChain() throws Exception {
            var error = new RuntimeException("not a timeout", new IllegalStateException("also not"));
            assertFalse(invokeIsTimeoutException(error));
        }

        @Test
        @DisplayName("shouldReturnFalseForNullClassName")
        void shouldReturnFalseForPlainException() throws Exception {
            assertFalse(invokeIsTimeoutException(new RuntimeException("plain error")));
        }

        @Test
        @DisplayName("shouldReturnTrueForNettyConnectTimeoutException")
        void shouldReturnTrueForNettyConnectTimeoutException() throws Exception {
            assertTrue(invokeIsTimeoutException(new io.netty.channel.ConnectTimeoutException("connect timed out")));
        }

        @Test
        @DisplayName("shouldReturnTrueForNestedNettyConnectTimeoutException")
        void shouldReturnTrueForNestedNettyConnectTimeoutException() throws Exception {
            var cause = new io.netty.channel.ConnectTimeoutException("connect timed out");
            var wrapper = new RuntimeException("wrapper", cause);
            assertTrue(invokeIsTimeoutException(wrapper));
        }
    }

    @Nested
    @DisplayName("classifyConnectionError()")
    class ClassifyConnectionError {

        private String invokeClassifyConnectionError(Throwable error) throws Exception {
            var method = ProxyHttpClient.class.getDeclaredMethod("classifyConnectionError", Throwable.class);
            method.setAccessible(true);
            return (String) method.invoke(proxyHttpClient, error);
        }

        @Test
        @DisplayName("shouldReturnConnectionRefusedWhenMessageContainsRefused")
        void shouldReturnConnectionRefusedWhenMessageContainsRefused() throws Exception {
            var error = new ConnectException("Connection refused");
            assertEquals("connection_refused", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnTlsHandshakeFailedForNestedTlsError")
        void shouldReturnTlsHandshakeFailed() throws Exception {
            var error = new RuntimeException("request failed", new javax.net.ssl.SSLHandshakeException("handshake"));
            assertEquals("tls_handshake_failed", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnConnectionResetWhenMessageContainsReset")
        void shouldReturnConnectionResetWhenMessageContainsReset() throws Exception {
            var error = new java.io.IOException("Connection reset by peer");
            assertEquals("connection_reset", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnHostUnreachableWhenMessageContainsUnreachable")
        void shouldReturnHostUnreachableWhenMessageContainsUnreachable() throws Exception {
            var error = new java.net.NoRouteToHostException("Host unreachable");
            assertEquals("host_unreachable", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnDnsResolutionFailedWhenMessageContainsResolve")
        void shouldReturnDnsResolutionFailedWhenMessageContainsResolve() throws Exception {
            var error = new UnknownHostException("Failed to resolve host");
            assertEquals("dns_resolution_failed", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnDnsResolutionFailedForUnknownHost")
        void shouldReturnDnsResolutionFailedForUnknownHost() throws Exception {
            var error = new RuntimeException("unknown host: backend.example.com");
            assertEquals("dns_resolution_failed", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldReturnGenericConnectionErrorForUnknownError")
        void shouldReturnGenericConnectionErrorForUnknownError() throws Exception {
            var error = new RuntimeException("something unexpected");
            assertEquals("connection_error", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldHandleNullMessage")
        void shouldHandleNullMessage() throws Exception {
            var error = new RuntimeException((String) null);
            assertEquals("connection_error", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldClassifyByClassNameContainingReset")
        void shouldClassifyByClassNameContainingReset() throws Exception {
            // java.net.SocketException with "reset" in message
            var error = new java.net.SocketException("Connection reset");
            assertEquals("connection_reset", invokeClassifyConnectionError(error));
        }

        @Test
        @DisplayName("shouldClassifyDnsFailureByClassNameContainingUnknownhost")
        void shouldClassifyDnsFailureByClassNameContainingUnknownhost() throws Exception {
            // UnknownHostException class name contains "unknownhost" (lowercase)
            var error = new UnknownHostException("no such host");
            assertEquals("dns_resolution_failed", invokeClassifyConnectionError(error));
        }
    }

    @Nested
    @DisplayName("getPort()")
    class GetPort {

        private int invokeGetPort(URI uri) throws Exception {
            var method = ProxyHttpClient.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);
            return (int) method.invoke(proxyHttpClient, uri);
        }

        @Test
        @DisplayName("shouldReturnExplicitPort")
        void shouldReturnExplicitPort() throws Exception {
            assertEquals(8443, invokeGetPort(URI.create("https://example.com:8443/api")));
        }

        @Test
        @DisplayName("shouldReturn443ForHttpsWithNoPort")
        void shouldReturn443ForHttpsWithNoPort() throws Exception {
            assertEquals(443, invokeGetPort(URI.create("https://example.com/api")));
        }

        @Test
        @DisplayName("shouldReturn80ForHttpWithNoPort")
        void shouldReturn80ForHttpWithNoPort() throws Exception {
            assertEquals(80, invokeGetPort(URI.create("http://example.com/api")));
        }
    }
}

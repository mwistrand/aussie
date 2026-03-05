package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.port.in.GatewayUseCase;

@DisplayName("GatewayResource")
@ExtendWith(MockitoExtension.class)
class GatewayResourceTest {

    @Mock
    private GatewayUseCase gatewayUseCase;

    @Mock
    private RoutingContext routingContext;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private UriInfo uriInfo;

    @Mock
    private HttpServerRequest httpServerRequest;

    @Mock
    private SocketAddress socketAddress;

    private GatewayResource resource;

    @BeforeEach
    void setUp() {
        resource = new GatewayResource(gatewayUseCase, routingContext);

        var headers = new MultivaluedHashMap<String, String>();
        headers.putSingle("Content-Type", "application/json");

        lenient().when(requestContext.getHeaders()).thenReturn(headers);
        lenient().when(requestContext.getMethod()).thenReturn("GET");
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080/gateway/api/users"));
        lenient().when(routingContext.request()).thenReturn(httpServerRequest);
        lenient().when(httpServerRequest.remoteAddress()).thenReturn(socketAddress);
        lenient().when(socketAddress.host()).thenReturn("192.168.1.1");
    }

    @Nested
    @DisplayName("Proxy methods")
    class ProxyMethods {

        @Test
        @DisplayName("proxyGet builds GatewayRequest with '/' + path and forwards via use case")
        void proxyGetBuildsRequestAndForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            var result = resource.proxyGet("api/users", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            var captured = captor.getValue();
            assertEquals("/api/users", captured.path());
            assertEquals(0, captured.body().length);
            assertEquals(200, result.getStatus());
        }

        @Test
        @DisplayName("proxyPost passes body to GatewayRequest")
        void proxyPostPassesBody() {
            var body = "test body".getBytes();
            var success = new GatewayResult.Success(201, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("POST");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyPost("api/users", requestContext, body).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertArrayEquals(body, captor.getValue().body());
        }

        @Test
        @DisplayName("proxyPut passes body to GatewayRequest")
        void proxyPutPassesBody() {
            var body = "update".getBytes();
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("PUT");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyPut("api/users/1", requestContext, body).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("/api/users/1", captor.getValue().path());
            assertArrayEquals(body, captor.getValue().body());
        }

        @Test
        @DisplayName("proxyDelete forwards with null body")
        void proxyDeleteForwardsWithNullBody() {
            var success = new GatewayResult.Success(204, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("DELETE");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyDelete("api/users/1", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("/api/users/1", captor.getValue().path());
        }

        @Test
        @DisplayName("proxyPatch passes body to GatewayRequest")
        void proxyPatchPassesBody() {
            var body = "patch".getBytes();
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("PATCH");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyPatch("api/users/1", requestContext, body).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertArrayEquals(body, captor.getValue().body());
        }

        @Test
        @DisplayName("proxyHead forwards with null body")
        void proxyHeadForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("HEAD");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyHead("api/status", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("/api/status", captor.getValue().path());
        }

        @Test
        @DisplayName("proxyOptions forwards with null body")
        void proxyOptionsForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("OPTIONS");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyOptions("api/users", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("/api/users", captor.getValue().path());
        }

        @Test
        @DisplayName("request headers are copied to GatewayRequest")
        void requestHeadersAreCopied() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer token123");
            headers.putSingle("X-Custom", "value");
            when(requestContext.getHeaders()).thenReturn(headers);

            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("api/test", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            var captured = captor.getValue();
            assertEquals(List.of("Bearer token123"), captured.headers().get("Authorization"));
            assertEquals(List.of("value"), captured.headers().get("X-Custom"));
        }

        @Test
        @DisplayName("request method is captured from ContainerRequestContext")
        void requestMethodIsCaptured() {
            when(requestContext.getMethod()).thenReturn("POST");
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyPost("api/test", requestContext, null).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("POST", captor.getValue().method());
        }

        @Test
        @DisplayName("request URI is captured from UriInfo")
        void requestUriIsCaptured() {
            var expectedUri = URI.create("http://localhost:8080/gateway/api/data?q=test");
            when(uriInfo.getRequestUri()).thenReturn(expectedUri);
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("api/data", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals(expectedUri, captor.getValue().requestUri());
        }
    }

    @Nested
    @DisplayName("extractClientIp")
    class ExtractClientIp {

        @Test
        @DisplayName("returns host from remote address")
        void returnsHostFromRemoteAddress() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("test", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertEquals("10.0.0.1", captor.getValue().clientIp());
        }

        @Test
        @DisplayName("returns null when remoteAddress is null")
        void returnsNullWhenRemoteAddressIsNull() {
            when(httpServerRequest.remoteAddress()).thenReturn(null);
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("test", requestContext).await().indefinitely();

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(gatewayUseCase).forward(captor.capture());
            assertNull(captor.getValue().clientIp());
        }
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("Success result maps status code, headers, and body")
        void successMapsStatusHeadersAndBody() {
            var responseHeaders = Map.of(
                    "X-Custom", List.of("val1", "val2"),
                    "Content-Type", List.of("text/plain"));
            var body = "response body".getBytes();
            var success = new GatewayResult.Success(201, responseHeaders, body);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            var response = resource.proxyGet("test", requestContext).await().indefinitely();

            assertEquals(201, response.getStatus());
            assertNotNull(response.getEntity());
        }

        @Test
        @DisplayName("Success with empty body does not set entity")
        void successWithEmptyBodyNoEntity() {
            var success = new GatewayResult.Success(204, Map.of(), new byte[0]);
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(success));

            var response = resource.proxyGet("test", requestContext).await().indefinitely();

            assertEquals(204, response.getStatus());
            assertNull(response.getEntity());
        }

        @Test
        @DisplayName("RouteNotFound throws HttpProblem with 404")
        void routeNotFoundThrows404() {
            var result = new GatewayResult.RouteNotFound("/unknown/path");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("unknown/path", requestContext)
                    .await()
                    .indefinitely());
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("ServiceNotFound throws HttpProblem with 404")
        void serviceNotFoundThrows404() {
            var result = new GatewayResult.ServiceNotFound("my-service");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("test", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("ReservedPath throws HttpProblem with 404")
        void reservedPathThrows404() {
            var result = new GatewayResult.ReservedPath("/admin");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("admin", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("Error throws HttpProblem with 502")
        void errorThrows502() {
            var result = new GatewayResult.Error("backend timeout");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("test", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.BAD_GATEWAY.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("Unauthorized throws HttpProblem with 401")
        void unauthorizedThrows401() {
            var result = new GatewayResult.Unauthorized("invalid token");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("test", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("Forbidden throws HttpProblem with 403")
        void forbiddenThrows403() {
            var result = new GatewayResult.Forbidden("access denied");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("test", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.FORBIDDEN.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("BadRequest throws HttpProblem with 400")
        void badRequestThrows400() {
            var result = new GatewayResult.BadRequest("missing parameter");
            when(gatewayUseCase.forward(any())).thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.proxyGet("test", requestContext).await().indefinitely());
            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }
}

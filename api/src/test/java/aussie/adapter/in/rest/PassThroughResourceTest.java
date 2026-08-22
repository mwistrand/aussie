package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
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

import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.port.in.PassThroughUseCase;

@DisplayName("PassThroughResource")
@ExtendWith(MockitoExtension.class)
class PassThroughResourceTest {

    @Mock
    private PassThroughUseCase passThroughUseCase;

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

    @Mock
    private ClientContextResolver clientContextResolver;

    private PassThroughResource resource;

    @BeforeEach
    void setUp() {
        resource = new PassThroughResource(passThroughUseCase, routingContext, clientContextResolver);

        var headers = new MultivaluedHashMap<String, String>();
        headers.putSingle("Content-Type", "application/json");

        lenient().when(requestContext.getHeaders()).thenReturn(headers);
        lenient().when(requestContext.getMethod()).thenReturn("GET");
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080/my-service/api/data"));
        lenient().when(routingContext.request()).thenReturn(httpServerRequest);
        lenient().when(httpServerRequest.remoteAddress()).thenReturn(socketAddress);
        lenient().when(socketAddress.host()).thenReturn("192.168.1.1");
        lenient()
                .when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext("192.168.1.1", false, null));
    }

    @Nested
    @DisplayName("Path normalization")
    class PathNormalization {

        @Test
        @DisplayName("empty path is normalized to '/'")
        void emptyPathNormalizedToSlash() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("my-service"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("my-service", "", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("my-service"), captor.capture());
            assertEquals("/", captor.getValue().path());
        }

        @Test
        @DisplayName("non-empty path is prefixed with '/'")
        void nonEmptyPathPrefixedWithSlash() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("my-service"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("my-service", "api/users", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("my-service"), captor.capture());
            assertEquals("/api/users", captor.getValue().path());
        }
    }

    @Nested
    @DisplayName("Proxy methods")
    class ProxyMethods {

        @Test
        @DisplayName("proxyGet forwards serviceId and request to use case")
        void proxyGetForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            var result =
                    resource.proxyGet("svc", "path", requestContext).await().atMost(Duration.ofSeconds(5));

            verify(passThroughUseCase).forward(eq("svc"), any());
            assertEquals(200, result.getStatus());
        }

        @Test
        @DisplayName("proxyPost passes body and serviceId")
        void proxyPostPassesBody() {
            var body = "post body".getBytes();
            var success = new GatewayResult.Success(201, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("POST");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyPost("svc", "api/items", requestContext, body).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertArrayEquals(body, captor.getValue().body());
            assertEquals("/api/items", captor.getValue().path());
        }

        @Test
        @DisplayName("proxyPut passes body and serviceId")
        void proxyPutPassesBody() {
            var body = "put body".getBytes();
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("PUT");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyPut("svc", "api/items/1", requestContext, body)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertArrayEquals(body, captor.getValue().body());
        }

        @Test
        @DisplayName("proxyDelete forwards with null body")
        void proxyDeleteForwards() {
            var success = new GatewayResult.Success(204, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("DELETE");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyDelete("svc", "api/items/1", requestContext).await().atMost(Duration.ofSeconds(5));

            verify(passThroughUseCase).forward(eq("svc"), any());
        }

        @Test
        @DisplayName("proxyPatch passes body and serviceId")
        void proxyPatchPassesBody() {
            var body = "patch body".getBytes();
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("PATCH");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyPatch("svc", "api/items/1", requestContext, body)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertArrayEquals(body, captor.getValue().body());
        }

        @Test
        @DisplayName("proxyHead forwards with null body")
        void proxyHeadForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("HEAD");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyHead("svc", "status", requestContext).await().atMost(Duration.ofSeconds(5));

            verify(passThroughUseCase).forward(eq("svc"), any());
        }

        @Test
        @DisplayName("proxyOptions forwards with null body")
        void proxyOptionsForwards() {
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(requestContext.getMethod()).thenReturn("OPTIONS");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyOptions("svc", "api/users", requestContext).await().atMost(Duration.ofSeconds(5));

            verify(passThroughUseCase).forward(eq("svc"), any());
        }

        @Test
        @DisplayName("request headers are copied to GatewayRequest")
        void requestHeadersAreCopied() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer abc");
            headers.put("Accept", List.of("text/html", "application/json"));
            when(requestContext.getHeaders()).thenReturn(headers);

            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertEquals(List.of("Bearer abc"), captor.getValue().headers().get("Authorization"));
            assertEquals(
                    List.of("text/html", "application/json"),
                    captor.getValue().headers().get("Accept"));
        }

        @Test
        @DisplayName("trusted external scheme is captured from the client context")
        void externalSchemeIsCaptured() {
            when(clientContextResolver.getOrCompute(routingContext))
                    .thenReturn(new ClientContext("10.0.0.1", true, "198.51.100.5", "https"));
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertEquals("https", captor.getValue().externalScheme());
        }
    }

    @Nested
    @DisplayName("extractClientIp")
    class ExtractClientIp {

        @Test
        @DisplayName("returns host from remote address")
        void returnsHostFromRemoteAddress() {
            when(clientContextResolver.getOrCompute(routingContext))
                    .thenReturn(new ClientContext("10.0.0.1", false, null));
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertEquals("10.0.0.1", captor.getValue().clientIp());
        }

        @Test
        @DisplayName("uses the fixed unknown identity when the peer is unavailable")
        void usesUnknownWhenRemoteAddressIsNull() {
            when(clientContextResolver.getOrCompute(routingContext)).thenReturn(new ClientContext(null, false, null));
            var success = new GatewayResult.Success(200, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            var captor = ArgumentCaptor.forClass(GatewayRequest.class);
            verify(passThroughUseCase).forward(eq("svc"), captor.capture());
            assertEquals("unknown", captor.getValue().clientIp());
        }
    }

    @Nested
    @DisplayName("toResponse")
    class ToResponse {

        @Test
        @DisplayName("Success result maps status code, headers, and body")
        void successMapsStatusHeadersAndBody() {
            var responseHeaders = Map.of("X-Custom", List.of("value"));
            var body = "response".getBytes();
            var success = new GatewayResult.Success(200, responseHeaders, body);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            var response =
                    resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertArrayEquals(body, (byte[]) response.getEntity());
        }

        @Test
        @DisplayName("Success with empty body does not set entity")
        void successWithEmptyBodyNoEntity() {
            var success = new GatewayResult.Success(204, Map.of(), new byte[0]);
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(success));

            var response =
                    resource.proxyGet("svc", "test", requestContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            assertNull(response.getEntity());
        }

        @Test
        @DisplayName("ServiceNotFound throws HttpProblem with 404")
        void serviceNotFoundThrows404() {
            var result = new GatewayResult.ServiceNotFound("unknown-svc");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "test", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("ReservedPath throws HttpProblem with 404")
        void reservedPathThrows404() {
            var result = new GatewayResult.ReservedPath("/admin");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "admin", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("RouteNotFound throws HttpProblem with 404")
        void routeNotFoundThrows404() {
            var result = new GatewayResult.RouteNotFound("/no-match");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "no-match", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Error throws HttpProblem with 502")
        void errorThrows502() {
            var result = new GatewayResult.Error("connection refused");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "test", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.BAD_GATEWAY.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Unauthorized throws HttpProblem with 401")
        void unauthorizedThrows401() {
            var result = new GatewayResult.Unauthorized("expired token");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "test", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Forbidden throws HttpProblem with 403")
        void forbiddenThrows403() {
            var result = new GatewayResult.Forbidden("insufficient permissions");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "test", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("BadRequest throws HttpProblem with 400")
        void badRequestThrows400() {
            var result = new GatewayResult.BadRequest("invalid input");
            when(passThroughUseCase.forward(eq("svc"), any()))
                    .thenReturn(Uni.createFrom().item(result));

            var ex = assertThrows(HttpProblem.class, () -> resource.proxyGet("svc", "test", requestContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }
    }
}

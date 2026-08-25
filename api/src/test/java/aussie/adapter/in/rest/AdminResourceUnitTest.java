package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("AdminResource")
@ExtendWith(MockitoExtension.class)
class AdminResourceUnitTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private CurrentIdentityAssociation identityAssociation;

    @Mock
    private GatewaySecurityConfig securityConfig;

    private AdminResource resource;

    @BeforeEach
    void setUp() {
        resource = new AdminResource(serviceRegistry, identityAssociation, securityConfig);
    }

    private ServiceRegistration createServiceRegistration(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("https://example.com"))
                .routePrefix("/" + serviceId)
                .defaultVisibility(EndpointVisibility.PRIVATE)
                .version(1)
                .build();
    }

    private ServiceRegistrationRequest createRequest(String serviceId) {
        return new ServiceRegistrationRequest(
                1L,
                serviceId,
                serviceId,
                "https://example.com",
                "/" + serviceId,
                "PRIVATE",
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private void mockIdentity(SecurityIdentity identity) {
        when(identityAssociation.getDeferredIdentity())
                .thenReturn(Uni.createFrom().item(identity));
    }

    private SecurityIdentity createIdentityWithPermissions(Set<String> permissions) {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getAttribute("permissions")).thenReturn(permissions);
        return identity;
    }

    private SecurityIdentity createIdempotencyIdentity(Set<String> permissions, String principalId) {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        lenient().when(identity.getAttribute("permissions")).thenReturn(permissions);
        lenient().when(identity.getAttribute("principalId")).thenReturn(principalId);
        lenient().when(identity.getAttribute("authenticationMethod")).thenReturn("test");
        return identity;
    }

    @Nested
    @DisplayName("registerService")
    class RegisterService {

        @Test
        @DisplayName("Should return CREATED when registration succeeds")
        void shouldReturnCreatedWhenRegistrationSucceeds() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var registration = createServiceRegistration("test-service");
            var identity = createIdentityWithPermissions(Set.of("service.config.create"));
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var request = createRequest("test-service");
            var response = resource.registerService(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            assertNotNull(response.getEntity());
            assertTrue(response.getEntity() instanceof ServiceRegistrationResponse);
            assertEquals("\"1\"", response.getHeaderString("ETag"));
        }

        @Test
        @DisplayName("Should replay an idempotent registration without repeating the write")
        void shouldReplayIdempotentRegistration() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var registration = createServiceRegistration("idempotent-service");
            var identity = createIdempotencyIdentity(Set.of("service.config.create"), "test-principal");
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var request = createRequest("idempotent-service");
            resource.registerService(request, null, "idempotency-test").await().atMost(Duration.ofSeconds(5));
            resource.registerService(request, null, "idempotency-test").await().atMost(Duration.ofSeconds(5));

            verify(serviceRegistry, times(1)).register(any(ServiceRegistration.class), any());
        }

        @Test
        @DisplayName("Should bind idempotency replay to the complete request")
        void shouldRejectIdempotencyKeyReuseWithDifferentPrecondition() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var registration = createServiceRegistration("idempotency-fingerprint-service");
            var identity = createIdempotencyIdentity(Set.of("service.config.create"), "test-principal");
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var request = createRequest("idempotency-fingerprint-service");
            resource.registerService(request, null, "idempotency-fingerprint-test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            var problem = assertThrows(
                    HttpProblem.class, () -> resource.registerService(request, "\"1\"", "idempotency-fingerprint-test")
                            .await()
                            .atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), problem.getStatusCode());
        }

        @Test
        @DisplayName("Should isolate idempotency keys by stable principal ID")
        void shouldIsolateIdempotencyKeysByPrincipalId() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var registration = createServiceRegistration("idempotency-actor-service");
            var firstIdentity = createIdempotencyIdentity(Set.of("service.config.create"), "first-principal");
            var secondIdentity = createIdempotencyIdentity(Set.of("service.config.create"), "second-principal");
            when(identityAssociation.getDeferredIdentity())
                    .thenReturn(
                            Uni.createFrom().item(firstIdentity),
                            Uni.createFrom().item(secondIdentity));
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var request = createRequest("idempotency-actor-service");
            resource.registerService(request, null, "shared-idempotency-test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            resource.registerService(request, null, "shared-idempotency-test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            verify(serviceRegistry, times(2)).register(any(ServiceRegistration.class), any());
        }

        @Test
        @DisplayName("Should accept a quoted If-Match service version")
        void shouldAcceptQuotedIfMatch() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var registration = createServiceRegistration("conditional-service");
            mockIdentity(createIdentityWithPermissions(Set.of("service.config.update")));
            when(serviceRegistry.register(any(ServiceRegistration.class), any(), eq(1L)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.registerService(createRequest("conditional-service"), "\"1\"", null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals("\"1\"", response.getHeaderString("ETag"));
        }

        @Test
        @DisplayName("Should reject a malformed If-Match service version")
        void shouldRejectMalformedIfMatch() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);

            var problem = assertThrows(
                    HttpProblem.class,
                    () -> resource.registerService(createRequest("conditional-service"), "\"1", null));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
        }

        @Test
        @DisplayName("Should throw 403 when registration is forbidden")
        void shouldThrow403WhenRegistrationForbidden() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var identity = createIdentityWithPermissions(Set.of("service.config.create"));
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Not authorized", 403)));

            var request = createRequest("test-service");
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.registerService(request).await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should throw 404 when registration not found")
        void shouldThrow404WhenRegistrationNotFound() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var identity = createIdentityWithPermissions(Set.of("service.config.create"));
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Service not found", 404)));

            var request = createRequest("test-service");
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.registerService(request).await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should throw 409 when registration conflicts")
        void shouldThrow409WhenRegistrationConflicts() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var identity = createIdentityWithPermissions(Set.of("service.config.create"));
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Version conflict", 409)));

            var request = createRequest("test-service");
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.registerService(request).await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should throw BAD_REQUEST when registration fails with default status")
        void shouldThrowBadRequestWhenRegistrationFailsWithDefaultStatus() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);
            var identity = createIdentityWithPermissions(Set.of("service.config.create"));
            mockIdentity(identity);
            when(serviceRegistry.register(any(ServiceRegistration.class), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Validation failed", 400)));

            var request = createRequest("test-service");
            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.registerService(request).await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should throw validation error when toModel throws IllegalArgumentException")
        void shouldThrowValidationErrorWhenToModelThrowsIllegalArgumentException() {
            when(securityConfig.allowPrivateUpstreams()).thenReturn(true);

            // A request with a blank serviceId will cause toModel to throw
            // IllegalArgumentException from the ServiceRegistration constructor
            var request = new ServiceRegistrationRequest(
                    1L,
                    " ",
                    "test",
                    "https://example.com",
                    "/test",
                    "PRIVATE",
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var ex = assertThrows(HttpProblem.class, () -> resource.registerService(request));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("unregisterService")
    class UnregisterService {

        @Test
        @DisplayName("Should return NO_CONTENT when unregistration succeeds")
        void shouldReturnNoContentWhenUnregistrationSucceeds() {
            var registration = createServiceRegistration("test-service");
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should throw when unregistration fails")
        void shouldThrowWhenUnregistrationFails() {
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Service not found", 404)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("listServices")
    class ListServices {

        @Test
        @DisplayName("Should return service list")
        void shouldReturnServiceList() {
            var service1 = createServiceRegistration("service-1");
            var service2 = createServiceRegistration("service-2");
            mockIdentity(createIdentityWithPermissions(Set.of("service.config.read")));
            when(serviceRegistry.getServicesAuthorized(25, 10, Set.of("service.config.read")))
                    .thenReturn(Uni.createFrom().item(List.of(service1, service2)));

            final var result = resource.listServices(25, 10).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
            assertEquals("service-1", result.get(0).serviceId());
            assertEquals("service-2", result.get(1).serviceId());
        }

        @Test
        @DisplayName("Should return empty list when no services")
        void shouldReturnEmptyListWhenNoServices() {
            mockIdentity(createIdentityWithPermissions(Set.of("service.config.read")));
            when(serviceRegistry.getServicesAuthorized(50, 0, Set.of("service.config.read")))
                    .thenReturn(Uni.createFrom().item(List.of()));

            final var result = resource.listServices(50, 0).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getService")
    class GetService {

        @Test
        @DisplayName("Should return OK when service found")
        void shouldReturnOkWhenServiceFound() {
            var registration = createServiceRegistration("test-service");
            var identity = createIdentityWithPermissions(Set.of("service.config.read"));
            mockIdentity(identity);
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            assertNotNull(response.getEntity());
            assertTrue(response.getEntity() instanceof ServiceRegistrationResponse);
        }

        @Test
        @DisplayName("Should throw when service not found")
        void shouldThrowWhenServiceNotFound() {
            var identity = createIdentityWithPermissions(Set.of("service.config.read"));
            mockIdentity(identity);
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Service not found", 404)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.getService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("extractClaims")
    class ExtractClaims {

        @Test
        @DisplayName("Should return empty claims when identity is null")
        void shouldReturnEmptyClaimsWhenIdentityIsNull() {
            when(identityAssociation.getDeferredIdentity())
                    .thenReturn(Uni.createFrom().item((SecurityIdentity) null));
            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of())))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should return empty claims when identity is anonymous")
        void shouldReturnEmptyClaimsWhenIdentityIsAnonymous() {
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(true);
            mockIdentity(identity);
            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of())))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should include permissions as Set in claims")
        void shouldIncludePermissionsAsSetInClaims() {
            var permissions = Set.of("service.config.read", "service.config.create");
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(permissions);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(permissions)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should ignore roles that were not expanded into permissions")
        void shouldIgnoreUnexpandedRoles() {
            var roles = Set.of("admin", "editor");
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(null);
            lenient().when(identity.getAttribute("roles")).thenReturn(roles);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of())))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should return empty claims when no permissions or roles")
        void shouldReturnEmptyClaimsWhenNoPermissionsOrRoles() {
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(null);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of())))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should combine permissions and roles in claims")
        void shouldCombinePermissionsAndRolesInClaims() {
            var permissions = Set.of("service.config.read");
            var roles = Set.of("admin");
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(permissions);
            lenient().when(identity.getAttribute("roles")).thenReturn(roles);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of("service.config.read"))))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }
    }

    @Nested
    @DisplayName("toGatewayProblem")
    class ToGatewayProblem {

        @Test
        @DisplayName("Should return 403 for forbidden failure")
        void shouldReturn403ForForbiddenFailure() {
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Not authorized", 403)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should return 404 for not found failure")
        void shouldReturn404ForNotFoundFailure() {
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Service not found", 404)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should return 409 for conflict failure")
        void shouldReturn409ForConflictFailure() {
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Version conflict", 409)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("Should return BAD_REQUEST for default status code")
        void shouldReturnBadRequestForDefaultStatusCode() {
            var identity = createIdentityWithPermissions(Set.of("service.config.delete"));
            mockIdentity(identity);
            when(serviceRegistry.unregisterAuthorized(eq("test-service"), any()))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.failure("Some other error", 500)));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.unregisterService("test-service").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }
    }
}

package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(identity.getAttribute("roles")).thenReturn(null);
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

            assertEquals(
                    Response.Status.FORBIDDEN.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.CONFLICT.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
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
            when(serviceRegistry.getAllServices()).thenReturn(Uni.createFrom().item(List.of(service1, service2)));

            var result = resource.listServices().await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
            assertEquals("service-1", result.get(0).serviceId());
            assertEquals("service-2", result.get(1).serviceId());
        }

        @Test
        @DisplayName("Should return empty list when no services")
        void shouldReturnEmptyListWhenNoServices() {
            when(serviceRegistry.getAllServices()).thenReturn(Uni.createFrom().item(List.of()));

            var result = resource.listServices().await().atMost(Duration.ofSeconds(5));

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

            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
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
            when(identity.getAttribute("roles")).thenReturn(null);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(permissions)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should include roles as Set in claims")
        void shouldIncludeRolesAsSetInClaims() {
            var roles = Set.of("admin", "editor");
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getAttribute("roles")).thenReturn(roles);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(roles)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(registration)));

            var response = resource.getService("test-service").await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Should include roles as List in claims")
        void shouldIncludeRolesAsListInClaims() {
            var rolesList = List.of("admin", "editor");
            var identity = mock(SecurityIdentity.class);
            when(identity.isAnonymous()).thenReturn(false);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getAttribute("roles")).thenReturn(rolesList);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of("admin", "editor"))))
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
            when(identity.getAttribute("roles")).thenReturn(null);
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
            when(identity.getAttribute("roles")).thenReturn(roles);
            mockIdentity(identity);

            var registration = createServiceRegistration("test-service");
            when(serviceRegistry.getServiceAuthorized(eq("test-service"), eq(Set.of("service.config.read", "admin"))))
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

            assertEquals(
                    Response.Status.FORBIDDEN.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.CONFLICT.getStatusCode(), ex.getStatus().getStatusCode());
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

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }
}

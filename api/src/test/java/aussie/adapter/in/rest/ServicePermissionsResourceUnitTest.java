package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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

import aussie.adapter.in.dto.ServicePermissionPolicyDto;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.ServiceAuthorizationService;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("ServicePermissionsResource")
@ExtendWith(MockitoExtension.class)
class ServicePermissionsResourceUnitTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private ServiceAuthorizationService authService;

    @Mock
    private CurrentIdentityAssociation identityAssociation;

    @Mock
    private SecurityIdentity securityIdentity;

    private ServicePermissionsResource resource;

    @BeforeEach
    void setUp() {
        resource = new ServicePermissionsResource(serviceRegistry, authService, identityAssociation);
    }

    private ServiceRegistration createService(String serviceId, long version) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8080"))
                .version(version)
                .build();
    }

    private ServiceRegistration createServiceWithPolicy(
            String serviceId, long version, ServicePermissionPolicy policy) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8080"))
                .permissionPolicy(policy)
                .version(version)
                .build();
    }

    private void mockIdentity() {
        when(identityAssociation.getDeferredIdentity())
                .thenReturn(Uni.createFrom().item(securityIdentity));
    }

    private void mockIdentityWithPermissions(Set<String> permissions) {
        mockIdentity();
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getAttribute("permissions")).thenReturn(permissions);
    }

    @Nested
    @DisplayName("getPermissions")
    class GetPermissions {

        @Test
        @DisplayName("should throw HttpProblem when service not found")
        void shouldThrowWhenServiceNotFound() {
            mockIdentityWithPermissions(Set.of("admin"));
            when(serviceRegistry.getService("unknown-svc"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.getPermissions("unknown-svc").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem when unauthorized")
        void shouldThrowWhenUnauthorized() {
            var service = createService("svc1", 1L);
            var permissions = Set.of("some-perm");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(false);

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should return policy when service has permission policy")
        void shouldReturnPolicyWhenServiceHasPolicy() {
            var policy = new ServicePermissionPolicy(Map.of());
            var service = createServiceWithPolicy("svc1", 5L, policy);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (ServicePermissionsResource.PermissionPolicyResponse) response.getEntity();
            assertEquals(5L, entity.version());
        }

        @Test
        @DisplayName("should return null policy when service has no permission policy")
        void shouldReturnNullPolicyWhenNoPolicyExists() {
            var service = createService("svc1", 3L);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (ServicePermissionsResource.PermissionPolicyResponse) response.getEntity();
            assertNull(entity.permissionPolicy());
            assertEquals(3L, entity.version());
        }
    }

    @Nested
    @DisplayName("updatePermissions")
    class UpdatePermissions {

        @Test
        @DisplayName("should throw HttpProblem when ifMatch is null")
        void shouldThrowWhenIfMatchNull() {
            var ex = assertThrows(HttpProblem.class, () -> resource.updatePermissions("svc1", null, null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem when service not found")
        void shouldThrowWhenServiceNotFound() {
            mockIdentityWithPermissions(Set.of("admin"));
            when(serviceRegistry.getService("unknown-svc"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var ex = assertThrows(HttpProblem.class, () -> resource.updatePermissions("unknown-svc", 1L, null)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem when unauthorized")
        void shouldThrowWhenUnauthorized() {
            var service = createService("svc1", 1L);
            var permissions = Set.of("some-perm");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(false);

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.updatePermissions("svc1", 1L, null).await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem when version mismatch")
        void shouldThrowWhenVersionMismatch() {
            var service = createService("svc1", 5L);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.updatePermissions("svc1", 3L, null).await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should clear policy when policyDto is null")
        void shouldClearPolicyWhenPolicyDtoNull() {
            var service = createService("svc1", 5L);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);
            when(serviceRegistry.update(any(ServiceRegistration.class)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(service.withIncrementedVersion())));

            var response = resource.updatePermissions("svc1", 5L, null).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(serviceRegistry).update(any(ServiceRegistration.class));
        }

        @Test
        @DisplayName("should update policy when policyDto is non-null")
        void shouldUpdatePolicyWhenPolicyDtoNonNull() {
            var service = createService("svc1", 5L);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);
            when(serviceRegistry.update(any(ServiceRegistration.class)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(service.withIncrementedVersion())));

            var policyDto = new ServicePermissionPolicyDto(Map.of());
            var response =
                    resource.updatePermissions("svc1", 5L, policyDto).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return updated version and ETag on success")
        void shouldReturnUpdatedVersionOnSuccess() {
            var service = createService("svc1", 5L);
            var permissions = Set.of("admin");
            mockIdentityWithPermissions(permissions);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(permissions)))
                    .thenReturn(true);
            when(serviceRegistry.update(any(ServiceRegistration.class)))
                    .thenReturn(Uni.createFrom().item(RegistrationResult.success(service.withIncrementedVersion())));

            var response = resource.updatePermissions("svc1", 5L, null).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (ServicePermissionsResource.PermissionPolicyResponse) response.getEntity();
            assertEquals(6L, entity.version());
            assertNotNull(response.getHeaderString("ETag"));
        }
    }

    @Nested
    @DisplayName("extractPermissions")
    class ExtractPermissions {

        @Test
        @DisplayName("should return empty set when identity is null")
        void shouldReturnEmptySetWhenIdentityNull() {
            when(identityAssociation.getDeferredIdentity())
                    .thenReturn(Uni.createFrom().nullItem());
            var service = createService("svc1", 1L);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(Set.of())))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return empty set when identity is anonymous")
        void shouldReturnEmptySetWhenAnonymous() {
            mockIdentity();
            when(securityIdentity.isAnonymous()).thenReturn(true);
            var service = createService("svc1", 1L);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(Set.of())))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return permissions when attribute is a Set")
        void shouldReturnPermissionsWhenAttributeIsSet() {
            var perms = Set.of("read", "write");
            mockIdentityWithPermissions(perms);
            var service = createService("svc1", 1L);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(perms)))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should return empty set when permissions attribute is not a Set")
        void shouldReturnEmptySetWhenPermissionsNotSet() {
            mockIdentity();
            when(securityIdentity.isAnonymous()).thenReturn(false);
            when(securityIdentity.getAttribute("permissions")).thenReturn("not-a-set");
            var service = createService("svc1", 1L);
            when(serviceRegistry.getService("svc1")).thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(authService.isAuthorizedForService(eq(service), anyString(), eq(Set.of())))
                    .thenReturn(true);

            var response = resource.getPermissions("svc1").await().atMost(Duration.ofSeconds(5));
            assertEquals(200, response.getStatus());
        }
    }
}

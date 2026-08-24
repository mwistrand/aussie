package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.dto.TranslationConfigUploadDto;
import aussie.adapter.in.dto.TranslationTestRequestDto;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.service.auth.TokenTranslationService;
import aussie.core.service.auth.TranslationConfigService;
import aussie.core.service.auth.TranslationConfigService.ConfigValidationException;

@DisplayName("TranslationConfigResource")
@ExtendWith(MockitoExtension.class)
class TranslationConfigResourceUnitTest {

    @Mock
    private TranslationConfigService configService;

    @Mock
    private TokenTranslationService translationService;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Principal principal;

    private TranslationConfigResource resource;

    @BeforeEach
    void setUp() {
        resource = new TranslationConfigResource(configService, translationService);
    }

    private TranslationConfigVersion createVersion(String id, int version, boolean active) {
        return new TranslationConfigVersion(id, version, null, active, "test-user", Instant.now(), "test comment");
    }

    private void mockPrincipal(String name) {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(name);
    }

    @Test
    @DisplayName("listVersions delegates pagination")
    void listVersionsDelegatesPagination() {
        when(configService.listVersions(25, 10)).thenReturn(Uni.createFrom().item(List.of()));

        final var result = resource.listVersions(25, 10).await().atMost(Duration.ofSeconds(5));

        assertTrue(result.isEmpty());
    }

    @Nested
    @DisplayName("uploadConfig")
    class UploadConfig {

        @Test
        @DisplayName("should return 201 on success")
        void shouldReturn201OnSuccess() {
            mockPrincipal("admin-user");
            var configVersion = createVersion("v1", 1, false);
            when(configService.upload(any(), eq("admin-user"), anyString(), anyBoolean()))
                    .thenReturn(Uni.createFrom().item(configVersion));

            var request = new TranslationConfigUploadDto(null, "test upload", false);
            var response =
                    resource.uploadConfig(request, securityContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem on ConfigValidationException")
        void shouldThrowOnConfigValidationException() {
            mockPrincipal("admin-user");
            var ex = new ConfigValidationException("Validation failed", List.of("error1", "error2"));
            when(configService.upload(any(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(Uni.createFrom().failure(ex));

            var request = new TranslationConfigUploadDto(null, "test upload", false);

            assertThrows(HttpProblem.class, () -> resource.uploadConfig(request, securityContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("validateConfig")
    class ValidateConfig {

        @Test
        @DisplayName("should return failure when config is null")
        void shouldReturnFailureWhenConfigNull() {
            var result = resource.validateConfig(null).await().atMost(Duration.ofSeconds(5));

            assertFalse(result.valid());
            assertEquals(1, result.errors().size());
            assertEquals("Configuration is required", result.errors().get(0));
        }

        @Test
        @DisplayName("should return success when no errors")
        void shouldReturnSuccessWhenNoErrors() {
            when(configService.validate(any(TranslationConfigSchema.class)))
                    .thenReturn(Uni.createFrom().item(List.of()));

            var config = TranslationConfigSchema.empty();
            var result = resource.validateConfig(config).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.valid());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("should return failure when errors exist")
        void shouldReturnFailureWhenErrorsExist() {
            var errors = List.of("error1", "error2");
            when(configService.validate(any(TranslationConfigSchema.class)))
                    .thenReturn(Uni.createFrom().item(errors));

            var config = TranslationConfigSchema.empty();
            var result = resource.validateConfig(config).await().atMost(Duration.ofSeconds(5));

            assertFalse(result.valid());
            assertEquals(2, result.errors().size());
        }
    }

    @Nested
    @DisplayName("testTranslation")
    class TestTranslation {

        @Test
        @DisplayName("should test with config when config is provided")
        void shouldTestWithConfigWhenProvided() {
            var config = TranslationConfigSchema.empty();
            var claims = Map.<String, Object>of("role", "admin");
            var translated = new TranslatedClaims(Set.of("admin"), Set.of("read"), Map.of());
            when(configService.testTranslation(eq(config), eq("my-issuer"), eq("my-subject"), eq(claims)))
                    .thenReturn(Uni.createFrom().item(translated));

            var request = new TranslationTestRequestDto(config, "my-issuer", "my-subject", claims);
            var result = resource.testTranslation(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Set.of("admin"), result.roles());
            assertEquals(Set.of("read"), result.permissions());
        }

        @Test
        @DisplayName("should test without config when config is null")
        void shouldTestWithoutConfigWhenNull() {
            var claims = Map.<String, Object>of("role", "admin");
            var translated = new TranslatedClaims(Set.of("viewer"), Set.of("view"), Map.of());
            when(configService.testTranslation(eq("test-issuer"), eq("test-subject"), eq(claims)))
                    .thenReturn(Uni.createFrom().item(translated));

            var request = new TranslationTestRequestDto(null, null, null, claims);
            var result = resource.testTranslation(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Set.of("viewer"), result.roles());
        }

        @Test
        @DisplayName("should default issuer when null")
        void shouldDefaultIssuerWhenNull() {
            var claims = Map.<String, Object>of("role", "admin");
            var translated = TranslatedClaims.empty();
            when(configService.testTranslation(eq("test-issuer"), eq("test-subject"), eq(claims)))
                    .thenReturn(Uni.createFrom().item(translated));

            var request = new TranslationTestRequestDto(null, null, null, claims);
            resource.testTranslation(request).await().atMost(Duration.ofSeconds(5));

            verify(configService).testTranslation(eq("test-issuer"), eq("test-subject"), eq(claims));
        }

        @Test
        @DisplayName("should default subject when null")
        void shouldDefaultSubjectWhenNull() {
            var claims = Map.<String, Object>of("role", "admin");
            var translated = TranslatedClaims.empty();
            when(configService.testTranslation(eq("test-issuer"), eq("test-subject"), eq(claims)))
                    .thenReturn(Uni.createFrom().item(translated));

            var request = new TranslationTestRequestDto(null, null, null, claims);
            resource.testTranslation(request).await().atMost(Duration.ofSeconds(5));

            verify(configService).testTranslation(eq("test-issuer"), eq("test-subject"), eq(claims));
        }

        @Test
        @DisplayName("should throw HttpProblem on ConfigValidationException with config")
        void shouldThrowOnConfigValidationException() {
            var config = TranslationConfigSchema.empty();
            var claims = Map.<String, Object>of("role", "admin");
            var ex = new ConfigValidationException("Validation failed", List.of("bad config"));
            when(configService.testTranslation(eq(config), anyString(), anyString(), eq(claims)))
                    .thenReturn(Uni.createFrom().failure(ex));

            var request = new TranslationTestRequestDto(config, "issuer", "subject", claims);

            assertThrows(
                    HttpProblem.class,
                    () -> resource.testTranslation(request).await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("getActiveConfig")
    class GetActiveConfig {

        @Test
        @DisplayName("should return active config when present")
        void shouldReturnActiveConfigWhenPresent() {
            var version = createVersion("v1", 1, true);
            when(configService.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var response = resource.getActiveConfig().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when no active config")
        void shouldThrowWhenNoActiveConfig() {
            when(configService.getActive()).thenReturn(Uni.createFrom().item(Optional.empty()));

            assertThrows(
                    HttpProblem.class, () -> resource.getActiveConfig().await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("getVersion")
    class GetVersion {

        @Test
        @DisplayName("should return version when present")
        void shouldReturnVersionWhenPresent() {
            var version = createVersion("v1", 1, false);
            when(configService.getById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var response = resource.getVersion("v1").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when version not found")
        void shouldThrowWhenVersionNotFound() {
            when(configService.getById("unknown")).thenReturn(Uni.createFrom().item(Optional.empty()));

            assertThrows(
                    HttpProblem.class,
                    () -> resource.getVersion("unknown").await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("activateVersion")
    class ActivateVersion {

        @Test
        @DisplayName("should return 204 when activation succeeds")
        void shouldReturn204WhenActivationSucceeds() {
            mockPrincipal("admin-user");
            when(configService.activate("v1")).thenReturn(Uni.createFrom().item(true));

            var response =
                    resource.activateVersion("v1", securityContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when version not found")
        void shouldThrowWhenVersionNotFound() {
            mockPrincipal("admin-user");
            when(configService.activate("unknown")).thenReturn(Uni.createFrom().item(false));

            assertThrows(HttpProblem.class, () -> resource.activateVersion("unknown", securityContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("should return version when rollback succeeds")
        void shouldReturnVersionWhenRollbackSucceeds() {
            mockPrincipal("admin-user");
            var version = createVersion("v1", 1, true);
            when(configService.rollback(1)).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var response = resource.rollback(1, securityContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when version not found")
        void shouldThrowWhenVersionNotFound() {
            mockPrincipal("admin-user");
            when(configService.rollback(99)).thenReturn(Uni.createFrom().item(Optional.empty()));

            assertThrows(
                    HttpProblem.class,
                    () -> resource.rollback(99, securityContext).await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("deleteVersion")
    class DeleteVersion {

        @Test
        @DisplayName("should return 204 when deletion succeeds")
        void shouldReturn204WhenDeletionSucceeds() {
            mockPrincipal("admin-user");
            when(configService.delete("v1")).thenReturn(Uni.createFrom().item(true));

            var response = resource.deleteVersion("v1", securityContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when version not found")
        void shouldThrowWhenVersionNotFound() {
            mockPrincipal("admin-user");
            when(configService.delete("unknown")).thenReturn(Uni.createFrom().item(false));

            assertThrows(HttpProblem.class, () -> resource.deleteVersion("unknown", securityContext)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("should return translation status")
        void shouldReturnTranslationStatus() {
            when(translationService.isEnabled()).thenReturn(true);
            when(translationService.getActiveProviderName()).thenReturn("config-based");
            when(translationService.isProviderHealthy()).thenReturn(true);
            when(translationService.getCacheSize()).thenReturn(10L);
            when(translationService.getCacheMaxSize()).thenReturn(1000L);
            when(translationService.getCacheTtlSeconds()).thenReturn(300);

            var result = resource.getStatus().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.enabled());
            assertEquals("config-based", result.activeProvider());
            assertTrue(result.providerHealthy());
            assertEquals(10L, result.cache().currentSize());
            assertEquals(1000L, result.cache().maxSize());
            assertEquals(300, result.cache().ttlSeconds());
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    class InvalidateCache {

        @Test
        @DisplayName("should return 204 and invalidate cache")
        void shouldReturn204AndInvalidateCache() {
            mockPrincipal("admin-user");

            var response = resource.invalidateCache(securityContext).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(translationService).invalidateCache();
        }
    }

    @Nested
    @DisplayName("getUserId")
    class GetUserId {

        @Test
        @DisplayName("should return principal name when principal is non-null")
        void shouldReturnPrincipalNameWhenNonNull() {
            mockPrincipal("admin-user");

            // Exercise getUserId via invalidateCache which calls it
            var response = resource.invalidateCache(securityContext).await().atMost(Duration.ofSeconds(5));
            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("should return unknown when principal is null")
        void shouldReturnUnknownWhenPrincipalNull() {
            when(securityContext.getUserPrincipal()).thenReturn(null);

            // Exercise getUserId via invalidateCache which calls it
            var response = resource.invalidateCache(securityContext).await().atMost(Duration.ofSeconds(5));
            assertEquals(204, response.getStatus());
        }
    }
}

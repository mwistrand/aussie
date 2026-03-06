package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigRepository;
import aussie.core.service.auth.TranslationConfigService.ConfigValidationException;

@DisplayName("TranslationConfigService")
@ExtendWith(MockitoExtension.class)
class TranslationConfigServiceTest {

    @Mock
    private TranslationConfigRepository repository;

    @Mock
    private TokenTranslationService translationService;

    private TranslationConfigService service;

    private TranslationConfigSchema validConfig;

    @BeforeEach
    void setUp() {
        service = new TranslationConfigService(repository, translationService);
        validConfig = new TranslationConfigSchema(
                1,
                List.of(new TranslationConfigSchema.ClaimSource(
                        "roles", "realm_access.roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                List.of(),
                new TranslationConfigSchema.Mappings(Map.of("admin", List.of("admin.*")), Map.of()),
                new TranslationConfigSchema.Defaults(true, false));
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("should return empty list for valid config")
        void shouldReturnEmptyForValidConfig() {
            var errors = service.validate(validConfig).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.isEmpty());
        }

        @Test
        @DisplayName("should return error for null config")
        void shouldReturnErrorForNullConfig() {
            var errors = service.validate(null).await().atMost(Duration.ofSeconds(5));
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("null"));
        }

        @Test
        @DisplayName("should return error for version less than 1")
        void shouldReturnErrorForInvalidVersion() {
            var config = new TranslationConfigSchema(
                    0,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("Version")));
        }

        @Test
        @DisplayName("should return error for null sources")
        void shouldReturnErrorForNullSources() {
            var config =
                    new TranslationConfigSchema(1, null, List.of(), TranslationConfigSchema.Mappings.empty(), null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("Sources")));
        }

        @Test
        @DisplayName("should return error for source with blank name")
        void shouldReturnErrorForSourceWithBlankName() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "", "claim", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("name")));
        }

        @Test
        @DisplayName("should return error for transform referencing unknown source")
        void shouldReturnErrorForOrphanedTransform() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(new TranslationConfigSchema.Transform(
                            "unknown", List.of(new TranslationConfigSchema.Operation.Lowercase()))),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("unknown source")));
        }

        @Test
        @DisplayName("should return error for null mappings")
        void shouldReturnErrorForNullMappings() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    null,
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("Mappings")));
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("should save version and return it")
        void shouldSaveAndReturn() {
            when(repository.getNextVersionNumber()).thenReturn(Uni.createFrom().item(1));
            when(repository.save(any())).thenReturn(Uni.createFrom().voidItem());

            var result = service.upload(validConfig, "test-user", "Initial upload", false)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            assertEquals(1, result.version());
            assertEquals("test-user", result.createdBy());
            assertEquals("Initial upload", result.comment());
            assertFalse(result.active());
        }

        @Test
        @DisplayName("should activate version when flag is true")
        void shouldActivateWhenFlagIsTrue() {
            when(repository.getNextVersionNumber()).thenReturn(Uni.createFrom().item(2));
            when(repository.save(any())).thenReturn(Uni.createFrom().voidItem());
            when(repository.setActive(any())).thenReturn(Uni.createFrom().item(true));

            var result =
                    service.upload(validConfig, "test-user", null, true).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.active());
        }

        @Test
        @DisplayName("should fail on validation error")
        void shouldFailOnValidationError() {
            var invalidConfig =
                    new TranslationConfigSchema(0, null, List.of(), TranslationConfigSchema.Mappings.empty(), null);

            assertThrows(ConfigValidationException.class, () -> service.upload(invalidConfig, "user", null, false)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("getActive")
    class GetActive {

        @Test
        @DisplayName("should return active version")
        void shouldReturnActiveVersion() {
            var version = TranslationConfigVersion.create("id", 1, validConfig, "user", null)
                    .activate();
            when(repository.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var result = service.getActive().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertTrue(result.get().active());
        }

        @Test
        @DisplayName("should return empty when no active version")
        void shouldReturnEmptyWhenNoActive() {
            when(repository.getActive()).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.getActive().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("activate")
    class Activate {

        @Test
        @DisplayName("should activate version and return true")
        void shouldActivateAndReturnTrue() {
            when(repository.setActive("version-id")).thenReturn(Uni.createFrom().item(true));

            var result = service.activate("version-id").await().atMost(Duration.ofSeconds(5));

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when version not found")
        void shouldReturnFalseWhenNotFound() {
            when(repository.setActive("missing-id")).thenReturn(Uni.createFrom().item(false));

            var result = service.activate("missing-id").await().atMost(Duration.ofSeconds(5));

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("rollback")
    class Rollback {

        @Test
        @DisplayName("should activate version by number")
        void shouldActivateByNumber() {
            var version = TranslationConfigVersion.create("id", 1, validConfig, "user", null);
            when(repository.findByVersion(1)).thenReturn(Uni.createFrom().item(Optional.of(version)));
            when(repository.setActive("id")).thenReturn(Uni.createFrom().item(true));

            var result = service.rollback(1).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertTrue(result.get().active());
        }

        @Test
        @DisplayName("should return empty when version not found")
        void shouldReturnEmptyWhenNotFound() {
            when(repository.findByVersion(99)).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.rollback(99).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete inactive version")
        void shouldDeleteInactiveVersion() {
            var version = TranslationConfigVersion.create("id", 1, validConfig, "user", null);
            when(repository.findById("id")).thenReturn(Uni.createFrom().item(Optional.of(version)));
            when(repository.delete("id")).thenReturn(Uni.createFrom().item(true));

            var result = service.delete("id").await().atMost(Duration.ofSeconds(5));

            assertTrue(result);
        }

        @Test
        @DisplayName("should fail to delete active version")
        void shouldFailToDeleteActive() {
            var version = TranslationConfigVersion.create("id", 1, validConfig, "user", null)
                    .activate();
            when(repository.findById("id")).thenReturn(Uni.createFrom().item(Optional.of(version)));

            assertThrows(
                    IllegalStateException.class,
                    () -> service.delete("id").await().atMost(Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("should return false when version not found")
        void shouldReturnFalseWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.delete("missing").await().atMost(Duration.ofSeconds(5));

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("should return version by ID")
        void shouldReturnVersionById() {
            var version = TranslationConfigVersion.create("v1", 1, validConfig, "user", null);
            when(repository.findById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var result = service.getById("v1").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals("v1", result.get().id());
        }

        @Test
        @DisplayName("should return empty when ID not found")
        void shouldReturnEmptyWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.getById("missing").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getByVersion")
    class GetByVersion {

        @Test
        @DisplayName("should return version by number")
        void shouldReturnVersionByNumber() {
            var version = TranslationConfigVersion.create("v1", 3, validConfig, "user", null);
            when(repository.findByVersion(3)).thenReturn(Uni.createFrom().item(Optional.of(version)));

            var result = service.getByVersion(3).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals(3, result.get().version());
        }

        @Test
        @DisplayName("should return empty when version number not found")
        void shouldReturnEmptyWhenNotFound() {
            when(repository.findByVersion(99)).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.getByVersion(99).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("listVersions")
    class ListVersions {

        @Test
        @DisplayName("should return all versions")
        void shouldReturnAllVersions() {
            var v1 = TranslationConfigVersion.create("v1", 1, validConfig, "user", null);
            var v2 = TranslationConfigVersion.create("v2", 2, validConfig, "user", null);
            when(repository.listVersions()).thenReturn(Uni.createFrom().item(List.of(v1, v2)));

            var result = service.listVersions().await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no versions")
        void shouldReturnEmptyList() {
            when(repository.listVersions()).thenReturn(Uni.createFrom().item(List.of()));

            var result = service.listVersions().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return paginated versions")
        void shouldReturnPaginatedVersions() {
            var v1 = TranslationConfigVersion.create("v1", 1, validConfig, "user", null);
            when(repository.listVersions(10, 0)).thenReturn(Uni.createFrom().item(List.of(v1)));

            var result = service.listVersions(10, 0).await().atMost(Duration.ofSeconds(5));

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("rollback edge cases")
    class RollbackEdgeCases {

        @Test
        @DisplayName("should return empty when setActive returns false")
        void shouldReturnEmptyWhenSetActiveFails() {
            var version = TranslationConfigVersion.create("id", 1, validConfig, "user", null);
            when(repository.findByVersion(1)).thenReturn(Uni.createFrom().item(Optional.of(version)));
            when(repository.setActive("id")).thenReturn(Uni.createFrom().item(false));

            var result = service.rollback(1).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("testTranslation")
    class TestTranslation {

        @Test
        @DisplayName("should delegate to translationService with config")
        void shouldDelegateWithConfig() {
            var claims = Map.<String, Object>of("roles", List.of("admin"));
            var expected = new TranslatedClaims(java.util.Set.of("admin"), java.util.Set.of(), Map.of());
            when(translationService.translateWithConfig(any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(expected));

            var result = service.testTranslation(validConfig, "issuer", "subject", claims)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(expected, result);
            verify(translationService).translateWithConfig(validConfig, "issuer", "subject", claims);
        }

        @Test
        @DisplayName("should fail on validation error")
        void shouldFailOnValidationError() {
            var invalidConfig =
                    new TranslationConfigSchema(0, null, List.of(), TranslationConfigSchema.Mappings.empty(), null);

            assertThrows(ConfigValidationException.class, () -> service.testTranslation(
                            invalidConfig, "issuer", "subject", Map.of())
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("should delegate to translationService for active config")
        void shouldDelegateForActiveConfig() {
            var claims = Map.<String, Object>of("roles", List.of("user"));
            var expected = new TranslatedClaims(java.util.Set.of("user"), java.util.Set.of(), Map.of());
            when(translationService.translate("issuer", "subject", claims))
                    .thenReturn(Uni.createFrom().item(expected));

            var result =
                    service.testTranslation("issuer", "subject", claims).await().atMost(Duration.ofSeconds(5));

            assertEquals(expected, result);
        }
    }

    @Nested
    @DisplayName("validate edge cases")
    class ValidateEdgeCases {

        @Test
        @DisplayName("should return error for source with null claim")
        void shouldReturnErrorForSourceWithNullClaim() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", null, TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("claim")));
        }

        @Test
        @DisplayName("should return error for source with blank claim")
        void shouldReturnErrorForSourceWithBlankClaim() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "  ", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("claim")));
        }

        @Test
        @DisplayName("should return error for source with null type")
        void shouldReturnErrorForSourceWithNullType() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource("roles", "roles", null)),
                    List.of(),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("type")));
        }

        @Test
        @DisplayName("should return error for transform with null source")
        void shouldReturnErrorForTransformWithNullSource() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(new TranslationConfigSchema.Transform(
                            null, List.of(new TranslationConfigSchema.Operation.Lowercase()))),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("source") && e.contains("null or blank")));
        }

        @Test
        @DisplayName("should return error for transform with empty operations")
        void shouldReturnErrorForTransformWithEmptyOperations() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(new TranslationConfigSchema.Transform("roles", List.of())),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("operation")));
        }

        @Test
        @DisplayName("should return error for transform with null operations")
        void shouldReturnErrorForTransformWithNullOperations() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(new TranslationConfigSchema.Transform("roles", null)),
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.stream().anyMatch(e -> e.contains("operation")));
        }

        @Test
        @DisplayName("should handle null transforms list without error")
        void shouldHandleNullTransforms() {
            var config = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    null,
                    TranslationConfigSchema.Mappings.empty(),
                    null);

            var errors = service.validate(config).await().atMost(Duration.ofSeconds(5));
            assertTrue(errors.isEmpty());
        }
    }

    @Nested
    @DisplayName("ConfigValidationException")
    class ConfigValidationExceptionTests {

        @Test
        @DisplayName("should return errors list")
        void shouldReturnErrorsList() {
            var errors = List.of("error1", "error2");
            var exception = new ConfigValidationException("Validation failed", errors);

            assertEquals(errors, exception.getErrors());
            assertTrue(exception.getMessage().contains("error1"));
            assertTrue(exception.getMessage().contains("error2"));
        }
    }
}

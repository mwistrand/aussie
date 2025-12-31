package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
            var errors = service.validate(validConfig).await().indefinitely();
            assertTrue(errors.isEmpty());
        }

        @Test
        @DisplayName("should return error for null config")
        void shouldReturnErrorForNullConfig() {
            var errors = service.validate(null).await().indefinitely();
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

            var errors = service.validate(config).await().indefinitely();
            assertTrue(errors.stream().anyMatch(e -> e.contains("Version")));
        }

        @Test
        @DisplayName("should return error for null sources")
        void shouldReturnErrorForNullSources() {
            var config =
                    new TranslationConfigSchema(1, null, List.of(), TranslationConfigSchema.Mappings.empty(), null);

            var errors = service.validate(config).await().indefinitely();
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

            var errors = service.validate(config).await().indefinitely();
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

            var errors = service.validate(config).await().indefinitely();
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

            var errors = service.validate(config).await().indefinitely();
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
                    .indefinitely();

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
                    service.upload(validConfig, "test-user", null, true).await().indefinitely();

            assertTrue(result.active());
        }

        @Test
        @DisplayName("should fail on validation error")
        void shouldFailOnValidationError() {
            var invalidConfig =
                    new TranslationConfigSchema(0, null, List.of(), TranslationConfigSchema.Mappings.empty(), null);

            assertThrows(ConfigValidationException.class, () -> service.upload(invalidConfig, "user", null, false)
                    .await()
                    .indefinitely());
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

            var result = service.getActive().await().indefinitely();

            assertTrue(result.isPresent());
            assertTrue(result.get().active());
        }

        @Test
        @DisplayName("should return empty when no active version")
        void shouldReturnEmptyWhenNoActive() {
            when(repository.getActive()).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.getActive().await().indefinitely();

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

            var result = service.activate("version-id").await().indefinitely();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when version not found")
        void shouldReturnFalseWhenNotFound() {
            when(repository.setActive("missing-id")).thenReturn(Uni.createFrom().item(false));

            var result = service.activate("missing-id").await().indefinitely();

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

            var result = service.rollback(1).await().indefinitely();

            assertTrue(result.isPresent());
            assertTrue(result.get().active());
        }

        @Test
        @DisplayName("should return empty when version not found")
        void shouldReturnEmptyWhenNotFound() {
            when(repository.findByVersion(99)).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.rollback(99).await().indefinitely();

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

            var result = service.delete("id").await().indefinitely();

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
                    () -> service.delete("id").await().indefinitely());
        }

        @Test
        @DisplayName("should return false when version not found")
        void shouldReturnFalseWhenNotFound() {
            when(repository.findById("missing")).thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.delete("missing").await().indefinitely();

            assertFalse(result);
        }
    }
}

package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

class CassandraMigrationRunnerTest {

    @Test
    void rejectsChangedLegacyMigrationsBeforeAdoptingTheirStatus() {
        final var session = mock(CqlSession.class);
        final var statementResult = mock(ResultSet.class);
        final var appliedMigrations = mock(ResultSet.class);
        final var appliedMigration = mock(Row.class);

        when(session.execute(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0, String.class).startsWith("SELECT version")
                                ? appliedMigrations
                                : statementResult);
        when(appliedMigrations.iterator()).thenReturn(List.of(appliedMigration).iterator());
        when(appliedMigration.getInt("version")).thenReturn(2);
        when(appliedMigration.getString("checksum")).thenReturn("changed");

        final var runner = new CassandraMigrationRunner(session, "aussie");

        final var failure = assertThrows(IllegalStateException.class, runner::runMigrations);

        assertEquals("Checksum mismatch for migration V2", failure.getMessage());
    }

    @Test
    void atomicallyClaimsPendingMigrations() {
        final var session = mock(CqlSession.class);
        final var statementResult = mock(ResultSet.class);
        final var appliedMigrations = mock(ResultSet.class);
        final var claimResult = mock(ResultSet.class);

        when(session.execute(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0, String.class).startsWith("SELECT version")
                                ? appliedMigrations
                                : statementResult);
        when(appliedMigrations.iterator()).thenReturn(List.<Row>of().iterator());
        when(session.execute(anyString(), any(Object[].class))).thenReturn(claimResult);
        when(claimResult.wasApplied()).thenReturn(false);

        final var runner = new CassandraMigrationRunner(session, "aussie");

        final var failure = assertThrows(IllegalStateException.class, runner::runMigrations);

        assertEquals("Migration V2 is already claimed", failure.getMessage());
    }

    @Test
    void atomicallyClaimsLegacyStartedMigrations() {
        final var session = mock(CqlSession.class);
        final var statementResult = mock(ResultSet.class);
        final var appliedMigrations = mock(ResultSet.class);
        final var appliedMigration = mock(Row.class);
        final var claimResult = mock(ResultSet.class);

        when(session.execute(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0, String.class).startsWith("SELECT version")
                                ? appliedMigrations
                                : statementResult);
        when(appliedMigrations.iterator()).thenReturn(List.of(appliedMigration).iterator());
        when(appliedMigration.getInt("version")).thenReturn(2);
        when(appliedMigration.getString("status")).thenReturn("STARTED");
        when(session.execute(anyString(), any(Object[].class))).thenReturn(claimResult);
        when(claimResult.wasApplied()).thenReturn(false);

        final var runner = new CassandraMigrationRunner(session, "aussie");

        assertThrows(IllegalStateException.class, runner::runMigrations);

        verify(session)
                .execute(
                        argThat((String cql) -> cql.contains("IF status = 'STARTED' AND lease_id = null")),
                        any(Object[].class));
    }

    @Test
    void rejectsLeaseRenewalAfterOwnershipChanges() {
        final var session = mock(CqlSession.class);
        final var renewalResult = mock(ResultSet.class);
        when(session.execute(anyString(), any(Instant.class), eq(2), anyString()))
                .thenReturn(renewalResult);
        when(renewalResult.wasApplied()).thenReturn(false);

        final var runner = new CassandraMigrationRunner(session, "aussie");

        final var failure = assertThrows(IllegalStateException.class, () -> runner.renewLease(2));

        assertEquals("Migration lease lost for V2", failure.getMessage());
    }

    @Test
    void makesPreManifestMigrationsSafeToAdopt() {
        assertEquals(
                "ALTER TABLE services ADD IF NOT EXISTS timeout text",
                CassandraMigrationRunner.migrationStatement(13, "ALTER TABLE services ADD timeout text"));
        assertEquals(
                "ALTER TABLE versions DROP IF EXISTS active",
                CassandraMigrationRunner.migrationStatement(10, "ALTER TABLE versions DROP active"));
        assertEquals("", CassandraMigrationRunner.migrationStatement(15, "DROP INDEX IF EXISTS old_index"));
    }

    @Test
    void backfillsLookupTables() {
        final var session = mock(CqlSession.class);
        final var apiKeys = mock(ResultSet.class);
        final var apiKey = mock(Row.class);
        final var versions = mock(ResultSet.class);
        final var version = mock(Row.class);
        final var createdAt = Instant.parse("2026-08-23T12:00:00Z");
        final var updatedAt = Instant.parse("2026-08-23T13:00:00Z");

        when(session.execute("SELECT key_id, key_hash, encrypted_data, created_at, updated_at FROM api_keys"))
                .thenReturn(apiKeys);
        when(apiKeys.iterator()).thenReturn(List.of(apiKey).iterator());
        when(apiKey.getString("key_hash")).thenReturn("hash");
        when(apiKey.getString("key_id")).thenReturn("key-id");
        when(apiKey.getString("encrypted_data")).thenReturn("encrypted-key");
        when(apiKey.getInstant("created_at")).thenReturn(createdAt);
        when(apiKey.getInstant("updated_at")).thenReturn(updatedAt);

        when(session.execute(argThat((String cql) -> cql.contains("FROM translation_config_versions"))))
                .thenReturn(versions);
        when(versions.iterator()).thenReturn(List.of(version).iterator());
        when(version.getInt("version")).thenReturn(7);
        when(version.getString("id")).thenReturn("version-id");
        when(version.getString("config_json")).thenReturn("{}");
        when(version.getString("created_by")).thenReturn("reviewer");
        when(version.getInstant("created_at")).thenReturn(createdAt);
        when(version.getString("comment")).thenReturn("reviewed");

        final var runner = new CassandraMigrationRunner(session, "aussie");

        assertDoesNotThrow(runner::backfillApiKeyHashLookup);
        assertDoesNotThrow(runner::backfillTranslationConfigVersionLookup);

        verify(session)
                .execute(
                        argThat((String cql) -> cql.contains("INSERT INTO api_keys_by_hash")),
                        eq("hash"),
                        eq("key-id"),
                        eq("encrypted-key"),
                        eq(createdAt),
                        eq(updatedAt));
        verify(session)
                .execute(
                        argThat((String cql) -> cql.contains("INSERT INTO translation_config_versions_by_number")),
                        eq(7),
                        eq("version-id"),
                        eq("{}"),
                        eq("reviewer"),
                        eq(createdAt),
                        eq("reviewed"));
    }

    @Test
    void backfillsOrderedTranslationVersionPages() {
        final var session = mock(CqlSession.class);
        final var versions = mock(ResultSet.class);
        final var version = mock(Row.class);
        final var createdAt = Instant.parse("2026-08-23T12:00:00Z");

        when(session.execute(argThat((String cql) -> cql.contains("FROM translation_config_versions"))))
                .thenReturn(versions);
        when(versions.iterator()).thenReturn(List.of(version).iterator());
        when(version.getInt("version")).thenReturn(3);
        when(version.getString("id")).thenReturn("version-id");
        when(version.getString("config_json")).thenReturn("{}");
        when(version.getString("created_by")).thenReturn("reviewer");
        when(version.getInstant("created_at")).thenReturn(createdAt);
        when(version.getString("comment")).thenReturn("reviewed");

        final var runner = new CassandraMigrationRunner(session, "aussie");

        assertDoesNotThrow(runner::backfillTranslationConfigVersionSequence);

        verify(session)
                .execute(
                        argThat((String cql) -> cql.contains("INSERT INTO translation_config_versions_by_sequence")),
                        eq(3),
                        eq("version-id"),
                        eq("{}"),
                        eq("reviewer"),
                        eq(createdAt),
                        eq("reviewed"));
    }
}

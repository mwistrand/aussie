package aussie.adapter.out.storage.cassandra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import org.jboss.logging.Logger;

/**
 * Runs CQL migrations on application startup.
 *
 * <p>Migration files are read from the classpath at db/cassandra/ and must follow
 * the naming convention: V{version}__{description}.cql (e.g., V1__create_keyspace.cql)
 *
 * <p>Applied migrations are tracked in the schema_migrations table to ensure
 * each migration is only applied once.
 */
public class CassandraMigrationRunner {

    private static final Logger LOG = Logger.getLogger(CassandraMigrationRunner.class);
    private static final String MIGRATIONS_PATH = "db/cassandra/";
    private static final Pattern MIGRATION_PATTERN = Pattern.compile("V(\\d+)__.*\\.cql");

    private final CqlSession session;
    private final String keyspace;

    public CassandraMigrationRunner(CqlSession session, String keyspace) {
        this.session = session;
        this.keyspace = keyspace;
    }

    /**
     * Run the keyspace creation migration (V1) without requiring a keyspace connection.
     * This should be called with a session that is not connected to any keyspace.
     */
    public void runKeyspaceMigration() {
        try {
            String content = readMigrationFile("V1__create_keyspace.cql");
            LOG.info("Ensuring keyspace exists...");

            String[] statements = content.split(";");
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                try {
                    session.execute(trimmed);
                } catch (Exception e) {
                    // Keyspace may already exist, which is fine
                    LOG.debugv("Statement result: {0}", e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warnv("Could not read keyspace migration: {0}", e.getMessage());
        }
    }

    /**
     * Run all pending migrations (V2 onwards).
     * This should be called with a session connected to the target keyspace.
     *
     * @return the number of migrations applied
     */
    public int runMigrations() {
        ensureMigrationTableExists();
        var appliedVersions = getAppliedVersions();
        var migrations = discoverMigrations();

        int applied = 0;
        for (var migration : migrations) {
            // Skip V1 - it's handled separately via runKeyspaceMigration()
            if (migration.version() == 1) {
                continue;
            }
            if (!appliedVersions.contains(migration.version())) {
                applyMigration(migration);
                applied++;
            }
        }

        if (applied > 0) {
            LOG.infov("Applied {0} migration(s)", applied);
        } else {
            LOG.debug("No pending migrations");
        }

        return applied;
    }

    private void ensureMigrationTableExists() {
        session.execute(
                """
                CREATE TABLE IF NOT EXISTS %s.schema_migrations (
                    version int PRIMARY KEY,
                    script_name text,
                    applied_at timestamp
                )
                """
                        .formatted(keyspace));
    }

    private Set<Integer> getAppliedVersions() {
        var rs = session.execute("SELECT version FROM %s.schema_migrations".formatted(keyspace));
        return rs.all().stream().map(row -> row.getInt("version")).collect(Collectors.toSet());
    }

    private List<Migration> discoverMigrations() {
        List<Migration> migrations = new ArrayList<>();

        try {
            var migrationFiles = listMigrationFiles();
            for (var filename : migrationFiles) {
                Matcher matcher = MIGRATION_PATTERN.matcher(filename);
                if (matcher.matches()) {
                    int version = Integer.parseInt(matcher.group(1));
                    String content = readMigrationFile(filename);
                    migrations.add(new Migration(version, filename, content));
                }
            }
        } catch (IOException e) {
            LOG.warnv("Failed to discover migrations: {0}", e.getMessage());
        }

        migrations.sort(Comparator.comparingInt(Migration::version));
        return migrations;
    }

    private List<String> listMigrationFiles() throws IOException {
        List<String> files = new ArrayList<>();

        // Read from classpath using the class loader
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(MIGRATIONS_PATH);
                BufferedReader reader =
                        is != null ? new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)) : null) {
            if (reader != null) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith(".cql")) {
                        files.add(line);
                    }
                }
            }
        }

        // Fallback: try to list known migration files if directory listing doesn't work
        if (files.isEmpty()) {
            files = listKnownMigrations();
        }

        return files;
    }

    private List<String> listKnownMigrations() {
        // Fallback list of known migrations - update this when adding new migrations
        List<String> known = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String prefix = "V" + i + "__";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(MIGRATIONS_PATH + prefix)) {
                // This won't work directly, so we try specific known files
            } catch (Exception ignored) {
            }
        }

        // Try specific known migration files
        String[] candidates = {
            "V1__create_keyspace.cql",
            "V2__create_tables.cql",
            "V3__create_api_keys_table.cql",
            "V4__add_default_auth_required.cql"
        };

        for (String candidate : candidates) {
            if (getClass().getClassLoader().getResource(MIGRATIONS_PATH + candidate) != null) {
                known.add(candidate);
            }
        }

        return known;
    }

    private String readMigrationFile(String filename) throws IOException {
        String path = MIGRATIONS_PATH + filename;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Migration file not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void applyMigration(Migration migration) {
        LOG.infov("Applying migration V{0}: {1}", migration.version(), migration.filename());

        // Split by semicolons and execute each statement
        String[] statements = migration.content().split(";");
        for (String statement : statements) {
            String trimmed = statement.trim();
            // Skip empty statements and comments
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            // Skip USE statements - we're already connected to the keyspace
            if (trimmed.toUpperCase().startsWith("USE ")) {
                continue;
            }
            try {
                session.execute(trimmed);
            } catch (Exception e) {
                LOG.errorv("Failed to execute statement in {0}: {1}", migration.filename(), e.getMessage());
                throw new RuntimeException("Migration failed: " + migration.filename(), e);
            }
        }

        // Record the migration
        session.execute(
                """
                INSERT INTO %s.schema_migrations (version, script_name, applied_at)
                VALUES (?, ?, ?)
                """
                        .formatted(keyspace),
                migration.version(),
                migration.filename(),
                Instant.now());

        LOG.infov("Migration V{0} applied successfully", migration.version());
    }

    private record Migration(int version, String filename, String content) {}
}

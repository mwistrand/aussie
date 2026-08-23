package aussie.adapter.out.storage.cassandra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import org.jboss.logging.Logger;

/** Runs the ordered, checksummed CQL migration manifest on application startup. */
public class CassandraMigrationRunner {

    private static final Logger LOG = Logger.getLogger(CassandraMigrationRunner.class);
    private static final String MIGRATIONS_PATH = "db/cassandra/";
    private static final String MANIFEST = MIGRATIONS_PATH + "migrations.manifest";
    private static final Pattern MIGRATION_PATTERN = Pattern.compile("V(\\d+)__.*\\.cql");
    private static final Pattern KEYSPACE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,47}");

    private final CqlSession session;
    private final String keyspace;

    public CassandraMigrationRunner(CqlSession session, String keyspace) {
        this.session = session;
        this.keyspace = validateKeyspace(keyspace);
    }

    /** Creates the configured keyspace without depending on a keyspace-bound session. */
    public void runKeyspaceMigration() {
        try {
            for (var statement : splitStatements(readMigrationFile("V1__create_keyspace.cql"))) {
                session.execute(statement.replace("aussie", keyspace));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read keyspace migration", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Keyspace migration failed", e);
        }
    }

    /** Applies every pending manifest entry and rejects changed applied scripts. */
    public int runMigrations() {
        ensureMigrationTableExists();
        final var migrations = discoverMigrations();
        final var applied = getAppliedMigrations();
        var count = 0;

        for (var migration : migrations) {
            if (migration.version() == 1) {
                continue;
            }
            if (applied.containsKey(migration.version())) {
                final var checksum = applied.get(migration.version());
                if (checksum == null) {
                    recordChecksum(migration);
                } else if (!migration.checksum().equals(checksum)) {
                    throw new IllegalStateException("Checksum mismatch for migration V" + migration.version());
                }
                continue;
            }
            applyMigration(migration);
            count++;
        }

        LOG.infov("Applied {0} migration(s)", count);
        return count;
    }

    private void ensureMigrationTableExists() {
        session.execute(
                """
                CREATE TABLE IF NOT EXISTS %s.schema_migrations (
                    version int PRIMARY KEY,
                    script_name text,
                    checksum text,
                    applied_at timestamp
                )
                """
                        .formatted(keyspace));

        session.execute("ALTER TABLE %s.schema_migrations ADD IF NOT EXISTS checksum text".formatted(keyspace));
    }

    private Map<Integer, String> getAppliedMigrations() {
        final var applied = new HashMap<Integer, String>();
        session.execute("SELECT version, checksum FROM %s.schema_migrations".formatted(keyspace))
                .forEach(row -> applied.put(row.getInt("version"), row.getString("checksum")));
        return applied;
    }

    private List<Migration> discoverMigrations() {
        try {
            final var migrations = new ArrayList<Migration>();
            final var versions = new HashSet<Integer>();
            for (var entry : readManifest()) {
                final Matcher matcher = MIGRATION_PATTERN.matcher(entry.filename());
                if (!matcher.matches()) {
                    throw new IllegalStateException("Invalid migration name: " + entry.filename());
                }
                final var version = Integer.parseInt(matcher.group(1));
                if (!versions.add(version)) {
                    throw new IllegalStateException("Duplicate migration version: V" + version);
                }
                final var content = readMigrationFile(entry.filename());
                final var checksum = sha256(content);
                if (!checksum.equals(entry.checksum())) {
                    throw new IllegalStateException("Migration manifest checksum mismatch: " + entry.filename());
                }
                migrations.add(new Migration(version, entry.filename(), content, checksum));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover migrations", e);
        }
    }

    private List<ManifestEntry> readManifest() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            if (input == null) {
                throw new IOException("Migration manifest not found: " + MANIFEST);
            }
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .map(line -> line.split("\\|", -1))
                        .map(parts -> {
                            if (parts.length != 2) {
                                throw new IllegalStateException("Invalid migration manifest entry");
                            }
                            return new ManifestEntry(parts[0], parts[1]);
                        })
                        .toList();
            }
        }
    }

    private String readMigrationFile(String filename) throws IOException {
        final var path = MIGRATIONS_PATH + filename;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Migration file not found: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void applyMigration(Migration migration) {
        LOG.infov("Applying migration V{0}: {1}", migration.version(), migration.filename());
        for (var statement : splitStatements(migration.content())) {
            final var executable = migrationStatement(migration.version(), statement);
            if (executable.isEmpty() || executable.toUpperCase(Locale.ROOT).startsWith("USE ")) {
                continue;
            }
            try {
                session.execute(executable);
            } catch (Exception e) {
                throw new IllegalStateException("Migration failed: " + migration.filename(), e);
            }
        }

        if (migration.version() == 17) {
            backfillApiKeyHashLookup();
            backfillTranslationConfigVersionLookup();
        }

        session.execute(
                """
                INSERT INTO %s.schema_migrations (version, script_name, checksum, applied_at)
                VALUES (?, ?, ?, ?)
                """
                        .formatted(keyspace),
                migration.version(),
                migration.filename(),
                migration.checksum(),
                Instant.now());
    }

    private void recordChecksum(Migration migration) {
        session.execute(
                "UPDATE %s.schema_migrations SET checksum = ? WHERE version = ?".formatted(keyspace),
                migration.checksum(),
                migration.version());
    }

    static String migrationStatement(int version, String statement) {
        final var uppercase = statement.toUpperCase(Locale.ROOT);
        if (version <= 13 && uppercase.startsWith("ALTER TABLE ")) {
            final var addIfMissing = statement.replaceFirst("(?i)\\bADD\\s+", "ADD IF NOT EXISTS ");
            return addIfMissing.equals(statement)
                    ? statement.replaceFirst("(?i)\\bDROP\\s+", "DROP IF EXISTS ")
                    : addIfMissing;
        }
        if ((version == 15 || version == 16) && uppercase.startsWith("DROP INDEX ")) {
            return "";
        }
        return statement;
    }

    void backfillApiKeyHashLookup() {
        for (var row :
                session.execute("SELECT key_id, key_hash, encrypted_data, created_at, updated_at FROM api_keys")) {
            session.execute(
                    """
                        INSERT INTO api_keys_by_hash (key_hash, key_id, encrypted_data, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                    row.getString("key_hash"),
                    row.getString("key_id"),
                    row.getString("encrypted_data"),
                    row.getInstant("created_at"),
                    row.getInstant("updated_at"));
        }
    }

    void backfillTranslationConfigVersionLookup() {
        for (var row : session.execute(
                """
                SELECT id, version, config_json, created_by, created_at, comment
                FROM translation_config_versions
                """)) {
            session.execute(
                    """
                        INSERT INTO translation_config_versions_by_number
                            (version, id, config_json, created_by, created_at, comment)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                    row.getInt("version"),
                    row.getString("id"),
                    row.getString("config_json"),
                    row.getString("created_by"),
                    row.getInstant("created_at"),
                    row.getString("comment"));
        }
    }

    private List<String> splitStatements(String content) {
        final var withoutComments = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private static String validateKeyspace(String keyspace) {
        if (keyspace == null || !KEYSPACE_PATTERN.matcher(keyspace).matches()) {
            throw new IllegalArgumentException("Invalid Cassandra keyspace name");
        }
        return keyspace;
    }

    private String sha256(String content) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record ManifestEntry(String filename, String checksum) {}

    private record Migration(int version, String filename, String content, String checksum) {}
}

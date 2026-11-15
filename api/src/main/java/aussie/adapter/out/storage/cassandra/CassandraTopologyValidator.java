package aussie.adapter.out.storage.cassandra;

import java.util.Map;

import com.datastax.oss.driver.api.core.ConsistencyLevel;

/** Validates the minimum Cassandra topology required by a production gateway. */
final class CassandraTopologyValidator {

    private CassandraTopologyValidator() {}

    static void validate(Map<String, String> replication, String localDatacenter) {
        final var strategy = replication.get("class");
        if (!"org.apache.cassandra.locator.NetworkTopologyStrategy".equals(strategy)
                && !"NetworkTopologyStrategy".equals(strategy)) {
            throw new IllegalStateException("Cassandra keyspace must use NetworkTopologyStrategy");
        }

        final int replicationFactor;
        try {
            replicationFactor = Integer.parseInt(replication.getOrDefault(localDatacenter, "0"));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Cassandra replication factor is not an integer", e);
        }
        if (replicationFactor < 2) {
            throw new IllegalStateException(
                    "Cassandra replication factor for " + localDatacenter + " must be at least 2");
        }
    }

    static ConsistencyLevel consistencyLevel(String configured) {
        return switch (configured.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ANY" -> ConsistencyLevel.ANY;
            case "ONE" -> ConsistencyLevel.ONE;
            case "TWO" -> ConsistencyLevel.TWO;
            case "THREE" -> ConsistencyLevel.THREE;
            case "QUORUM" -> ConsistencyLevel.QUORUM;
            case "ALL" -> ConsistencyLevel.ALL;
            case "LOCAL_ONE" -> ConsistencyLevel.LOCAL_ONE;
            case "LOCAL_QUORUM" -> ConsistencyLevel.LOCAL_QUORUM;
            case "EACH_QUORUM" -> ConsistencyLevel.EACH_QUORUM;
            case "SERIAL", "LOCAL_SERIAL" ->
                throw new IllegalArgumentException("Serial consistency requires a separate serial consistency setting");
            default -> throw new IllegalArgumentException("Unknown Cassandra consistency level: " + configured);
        };
    }
}

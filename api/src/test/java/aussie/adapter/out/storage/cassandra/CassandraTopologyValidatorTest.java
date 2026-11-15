package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CassandraTopologyValidatorTest {

    @Test
    void acceptsNetworkTopologyWithTwoReplicasInTheLocalDatacenter() {
        assertDoesNotThrow(() -> CassandraTopologyValidator.validate(
                Map.of("class", "org.apache.cassandra.locator.NetworkTopologyStrategy", "datacenter1", "2"),
                "datacenter1"));
    }

    @Test
    void rejectsSimpleStrategy() {
        assertThrows(
                IllegalStateException.class,
                () -> CassandraTopologyValidator.validate(
                        Map.of("class", "org.apache.cassandra.locator.SimpleStrategy", "replication_factor", "3"),
                        "datacenter1"));
    }

    @Test
    void rejectsSingleReplicaLocalDatacenter() {
        assertThrows(
                IllegalStateException.class,
                () -> CassandraTopologyValidator.validate(
                        Map.of("class", "NetworkTopologyStrategy", "datacenter1", "1"), "datacenter1"));
    }

    @Test
    void rejectsSerialConsistencyForRegularRequests() {
        assertThrows(IllegalArgumentException.class, () -> CassandraTopologyValidator.consistencyLevel("SERIAL"));
        assertThrows(IllegalArgumentException.class, () -> CassandraTopologyValidator.consistencyLevel("LOCAL_SERIAL"));
    }
}

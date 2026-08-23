package aussie.core.model.routing;

import java.time.Instant;
import java.util.Optional;

/** Operator-visible state of the local immutable routing snapshot. */
public record RoutingSnapshotStatus(
        long activeGeneration,
        long durableGeneration,
        long convergenceLag,
        String checksum,
        Optional<RejectedGeneration> lastRejectedGeneration) {

    public record RejectedGeneration(long generation, String reason, Instant rejectedAt) {}
}

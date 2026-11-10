package aussie.core.model.routing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Operator-visible state of the local immutable routing snapshot. */
public record RoutingSnapshotStatus(
        long activeGeneration,
        long durableGeneration,
        long convergenceLag,
        String checksum,
        Optional<RejectedGeneration> lastRejectedGeneration,
        long snapshotAgeSeconds,
        Optional<String> lastPropagationErrorClass,
        int reconciliationBacklog,
        List<PendingPropagation> pendingPropagations) {

    public RoutingSnapshotStatus(
            long activeGeneration,
            long durableGeneration,
            long convergenceLag,
            String checksum,
            Optional<RejectedGeneration> lastRejectedGeneration) {
        this(
                activeGeneration,
                durableGeneration,
                convergenceLag,
                checksum,
                lastRejectedGeneration,
                -1L,
                Optional.empty(),
                0,
                List.of());
    }

    public RoutingSnapshotStatus(
            long activeGeneration,
            long durableGeneration,
            long convergenceLag,
            String checksum,
            Optional<RejectedGeneration> lastRejectedGeneration,
            long snapshotAgeSeconds,
            Optional<String> lastPropagationErrorClass,
            int reconciliationBacklog) {
        this(
                activeGeneration,
                durableGeneration,
                convergenceLag,
                checksum,
                lastRejectedGeneration,
                snapshotAgeSeconds,
                lastPropagationErrorClass,
                reconciliationBacklog,
                List.of());
    }

    public record RejectedGeneration(long generation, String reason, Instant rejectedAt) {}

    public record PendingPropagation(String serviceId, long version) {}
}

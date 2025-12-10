package aussie.telemetry.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe sliding window counter for rate limiting and anomaly detection.
 *
 * <p>This counter tracks events within a configurable time window, automatically
 * expiring old entries as time passes. It is designed for high-throughput
 * scenarios with minimal contention.
 *
 * <p>Example usage:
 * <pre>{@code
 * var counter = new SlidingWindowCounter(Duration.ofMinutes(1));
 * counter.increment();
 * long count = counter.getCount(); // Events in the last minute
 * }</pre>
 */
public class SlidingWindowCounter {

    private final Duration window;
    private final ConcurrentLinkedDeque<Long> timestamps;
    private final AtomicLong approximateCount;

    /**
     * Creates a new sliding window counter.
     *
     * @param window the time window for counting events
     */
    public SlidingWindowCounter(Duration window) {
        this.window = window;
        this.timestamps = new ConcurrentLinkedDeque<>();
        this.approximateCount = new AtomicLong(0);
    }

    /**
     * Records an event at the current time.
     */
    public void increment() {
        long now = System.currentTimeMillis();
        timestamps.addLast(now);
        approximateCount.incrementAndGet();
        cleanup(now);
    }

    /**
     * Returns the count of events within the time window.
     *
     * <p>This method performs cleanup of expired entries before counting,
     * ensuring an accurate result.
     *
     * @return number of events in the current window
     */
    public long getCount() {
        cleanup(System.currentTimeMillis());
        return timestamps.size();
    }

    /**
     * Returns an approximate count without triggering cleanup.
     *
     * <p>This is faster than {@link #getCount()} but may include
     * recently expired entries.
     *
     * @return approximate number of events
     */
    public long getApproximateCount() {
        return approximateCount.get();
    }

    /**
     * Checks if the count exceeds the given threshold.
     *
     * @param threshold the threshold to check against
     * @return true if count exceeds threshold
     */
    public boolean exceeds(int threshold) {
        return getCount() > threshold;
    }

    /**
     * Returns the configured window duration.
     *
     * @return the window duration
     */
    public Duration getWindow() {
        return window;
    }

    /**
     * Resets the counter, clearing all recorded events.
     */
    public void reset() {
        timestamps.clear();
        approximateCount.set(0);
    }

    /**
     * Removes expired entries from the front of the deque.
     */
    private void cleanup(long now) {
        long cutoff = now - window.toMillis();
        Long oldest;
        while ((oldest = timestamps.peekFirst()) != null && oldest < cutoff) {
            if (timestamps.pollFirst() != null) {
                approximateCount.decrementAndGet();
            }
        }
    }

    /**
     * Returns the timestamp of the oldest entry, or empty if none.
     *
     * @return oldest timestamp in epoch millis, or -1 if empty
     */
    public long getOldestTimestamp() {
        Long oldest = timestamps.peekFirst();
        return oldest != null ? oldest : -1;
    }

    /**
     * Returns the timestamp of the newest entry, or empty if none.
     *
     * @return newest timestamp in epoch millis, or -1 if empty
     */
    public long getNewestTimestamp() {
        Long newest = timestamps.peekLast();
        return newest != null ? newest : -1;
    }
}

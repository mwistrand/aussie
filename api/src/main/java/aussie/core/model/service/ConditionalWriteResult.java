package aussie.core.model.service;

import java.util.OptionalLong;

/** Result of a conditional repository mutation. */
public record ConditionalWriteResult(boolean applied, OptionalLong currentVersion) {

    public static ConditionalWriteResult appliedResult() {
        return new ConditionalWriteResult(true, OptionalLong.empty());
    }

    public static ConditionalWriteResult rejected(long currentVersion) {
        return new ConditionalWriteResult(false, OptionalLong.of(currentVersion));
    }

    public static ConditionalWriteResult missing() {
        return new ConditionalWriteResult(false, OptionalLong.empty());
    }
}

package aussie.adapter.out;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import aussie.spi.StorageProviderException;

public final class ProviderSelection {

    private ProviderSelection() {}

    public static <T> T select(
            List<? extends T> providers,
            String configured,
            String type,
            Function<? super T, String> name,
            ToIntFunction<? super T> priority,
            Predicate<? super T> available) {
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(provider -> name.apply(provider).equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException("Configured " + type + " provider not found: "
                            + configured + ". Available: "
                            + providers.stream().map(name).toList()));
        }

        return providers.stream()
                .filter(available)
                .max(Comparator.comparingInt(priority))
                .orElseThrow(() -> new StorageProviderException("No available " + type + " providers"));
    }
}

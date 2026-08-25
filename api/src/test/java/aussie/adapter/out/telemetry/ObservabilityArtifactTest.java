package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ObservabilityArtifactTest {

    private static final Pattern ALERT = Pattern.compile("(?ms)^      - alert:.*?(?=^      - alert:|\\z)");

    @Test
    void everyAlertHasAnOwnerAndRunbook() throws Exception {
        final var alertFiles = new Path[] {
            Path.of("../monitoring/prometheus/alerts/aussie.yaml"),
            Path.of("../monitoring/prometheus/alerts/aussie-slos.yaml")
        };

        for (var file : alertFiles) {
            final var text = Files.readString(file);
            final var matcher = ALERT.matcher(text);
            var alerts = 0;
            while (matcher.find()) {
                alerts++;
                assertTrue(matcher.group().contains("owner: "), file + " has an ownerless alert");
                assertTrue(matcher.group().contains("runbook_url: "), file + " has an alert without a runbook");
            }
            assertTrue(alerts > 0, file + " contains no alerts");
        }
    }
}

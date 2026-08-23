package aussie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class AuthenticationSurfacePolicyTest {

    @Test
    void checkedInEndpointPoliciesMatchThePublishedInventory() throws IOException {
        final var properties = new Properties();
        try (var input = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            assertNotNull(input);
            properties.load(input);
        }

        final var expected = Map.of(
                "quarkus.http.auth.permission.admin.paths", "/admin/*",
                "quarkus.http.auth.permission.admin.policy", "authenticated",
                "quarkus.http.auth.permission.gateway.paths", "/gateway/*",
                "quarkus.http.auth.permission.gateway.policy", "permit",
                "quarkus.http.auth.permission.health.paths", "/q/*",
                "quarkus.http.auth.permission.health.policy", "permit",
                "quarkus.http.auth.permission.passthrough.paths", "/*",
                "quarkus.http.auth.permission.passthrough.policy", "permit",
                "aussie.session.public-creation-enabled", "false",
                "aussie.auth.oidc.public-endpoints-enabled", "false");

        expected.forEach((name, value) -> assertEquals(value, properties.getProperty(name), name));
    }
}

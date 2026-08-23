package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IssuedClaimPolicy")
class IssuedClaimPolicyTest {

    @Test
    @DisplayName("allows bounded JSON claims and rejects oversized or opaque values")
    void enforcesClaimBoundary() {
        assertTrue(IssuedClaimPolicy.isAllowed("groups", List.of("reader", "writer")));
        assertTrue(IssuedClaimPolicy.isAllowed("context", Map.of("tenant", "example")));
        assertFalse(IssuedClaimPolicy.isAllowed("bio", "x".repeat(4097)));
        assertFalse(IssuedClaimPolicy.isAllowed("score", Double.NaN));
        assertFalse(IssuedClaimPolicy.isAllowed("counter", new BigInteger("1".repeat(4097))));
        assertFalse(IssuedClaimPolicy.isAllowed("opaque", new Object()));
    }
}

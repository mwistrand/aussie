package aussie.core.port.out;

import java.time.Instant;
import java.util.Optional;

/** Local acceleration port for confirmed token-revocation checks. */
public interface RevocationCache {

    Optional<Boolean> isJtiRevoked(String jti);

    Optional<Boolean> isUserRevoked(String userId, Instant issuedAt);

    void cacheJtiRevocation(String jti, Instant expiresAt);

    void cacheUserRevocation(String userId, Instant issuedBefore, Instant expiresAt);

    void invalidateAll();

    boolean isEnabled();
}

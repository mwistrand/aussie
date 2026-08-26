package aussie.core.port.out;

import io.smallrye.mutiny.Uni;

/** Local acceleration port for authoritative token-revocation checks. */
public interface RevocationBloomFilter {

    boolean definitelyNotRevoked(String jti);

    boolean userDefinitelyNotRevoked(String userId);

    void addRevokedJti(String jti);

    void addRevokedUser(String userId);

    Uni<Void> rebuildFilters();

    boolean isEnabled();
}

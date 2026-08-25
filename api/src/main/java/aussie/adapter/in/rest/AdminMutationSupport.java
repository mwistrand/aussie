package aussie.adapter.in.rest;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.ws.rs.core.Response;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.util.SecureHash;

/** Small shared guard for tenant ownership and retried admin mutations. */
final class AdminMutationSupport {

    private static final Logger AUDIT = Logger.getLogger("aussie.audit.admin");
    // ponytail: local cache remains only for dev/tests; production enables the Redis store below.
    private static final Cache<IdempotencyKey, Entry> OPERATIONS = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private AdminMutationSupport() {}

    static Uni<Response> idempotent(
            RedisAdminMutationStore distributedStore,
            SecurityIdentity identity,
            String scope,
            String key,
            Object fingerprint,
            Supplier<Uni<Response>> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (key.length() > 128) {
            throw GatewayProblem.badRequest("Idempotency-Key must be 128 characters or less");
        }

        var cacheKey = new IdempotencyKey(
                scope,
                identityAttribute(identity, "authenticationMethod"),
                identityAttribute(identity, "issuer"),
                principalId(identity),
                key);
        var existing = OPERATIONS.getIfPresent(cacheKey);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw GatewayProblem.conflict("Idempotency-Key was already used for a different request");
            }
            return existing.result();
        }

        if (distributedStore != null && distributedStore.enabled()) {
            return distributedStore.execute(operationKey(cacheKey), fingerprint, action);
        }

        var result = action.get().memoize().indefinitely();
        var prior = OPERATIONS.asMap().putIfAbsent(cacheKey, new Entry(fingerprint, result));
        if (prior == null) {
            return result;
        }
        return prior.fingerprint().equals(fingerprint)
                ? prior.result()
                : Uni.createFrom()
                        .failure(GatewayProblem.conflict("Idempotency-Key was already used for a different request"));
    }

    static boolean canSee(SecurityIdentity identity, String ownerTeam) {
        if (isGlobal(identity)) {
            return true;
        }
        var callerTeam = team(identity);
        return callerTeam != null && callerTeam.equals(ownerTeam);
    }

    static String etag(long version) {
        return VersionPreconditions.etag(version);
    }

    static void requireMatchingEtag(String header, long version) {
        if ("*".equals(header)) {
            return;
        }
        final var expectedVersion = VersionPreconditions.parseIfMatch(header);
        if (expectedVersion == null || expectedVersion != version) {
            throw GatewayProblem.preconditionFailed("The resource changed; fetch it again before retrying");
        }
    }

    static String requireTeam(SecurityIdentity identity, String requestedTeam) {
        if (isGlobal(identity)) {
            return requestedTeam;
        }
        var callerTeam = team(identity);
        if (callerTeam == null) {
            throw GatewayProblem.forbidden("A tenant is required for this mutation");
        }
        if (requestedTeam != null && !callerTeam.equals(requestedTeam)) {
            throw GatewayProblem.forbidden("The requested tenant is not accessible");
        }
        return callerTeam;
    }

    static String team(SecurityIdentity identity) {
        Object value = identity == null ? null : identity.getAttribute("teamId");
        return value == null ? null : value.toString();
    }

    static boolean isGlobal(SecurityIdentity identity) {
        if (identity == null) {
            return true;
        }
        if (identity.hasRole("admin")) {
            return true;
        }
        Object permissions = identity.getAttribute("permissions");
        if (permissions instanceof Set<?> values) {
            return values.contains("*") || values.contains("admin");
        }
        // Keep direct unit/resource construction usable; real authenticated identities have a principal.
        return identity.getPrincipal() == null && team(identity) == null;
    }

    static void audit(
            RedisAdminMutationStore distributedStore,
            SecurityIdentity identity,
            String action,
            String target,
            String outcome) {
        var actor = actor(identity);
        var safeTarget = safe(target);
        AUDIT.infof("admin_mutation action=%s actor=%s target=%s outcome=%s", action, actor, safeTarget, outcome);
        if (distributedStore != null && distributedStore.enabled()) {
            distributedStore
                    .appendAudit(actor, action, safeTarget, outcome)
                    .subscribe()
                    .with(
                            ignored -> {},
                            error -> AUDIT.warnf(
                                    "Durable admin audit write failed: %s",
                                    error.getClass().getSimpleName()));
        }
    }

    private static String actor(SecurityIdentity identity) {
        var principalId = principalId(identity);
        var issuer = identityAttribute(identity, "issuer");
        return safe(issuer == null ? principalId : issuer + ":" + principalId);
    }

    private static String principalId(SecurityIdentity identity) {
        var principalId = identityAttribute(identity, "principalId");
        if (principalId != null) {
            return principalId;
        }
        var principal = identity == null ? null : identity.getPrincipal();
        var name = principal == null ? "anonymous" : principal.getName();
        return name == null ? "unknown" : name;
    }

    private static String identityAttribute(SecurityIdentity identity, String name) {
        Object value = identity == null ? null : identity.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.:@-]", "_");
    }

    private static String operationKey(IdempotencyKey key) {
        var value = new StringBuilder();
        append(value, key.scope());
        append(value, key.authenticationMethod());
        append(value, key.issuer());
        append(value, key.principalId());
        append(value, key.key());
        return SecureHash.truncatedSha256(value.toString(), 64);
    }

    private static void append(StringBuilder target, String value) {
        target.append(value == null ? -1 : value.length()).append(':');
        if (value != null) {
            target.append(value);
        }
    }

    private record IdempotencyKey(
            String scope, String authenticationMethod, String issuer, String principalId, String key) {}

    private record Entry(Object fingerprint, Uni<Response> result) {}
}

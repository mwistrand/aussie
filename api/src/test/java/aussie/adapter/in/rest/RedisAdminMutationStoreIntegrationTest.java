package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RedisAdminMutationStoreIntegrationTest {

    @Inject
    RedisAdminMutationStore store;

    @Test
    void replaysAcrossTheRedisCommandPath() {
        var calls = new AtomicInteger();
        var operation = UUID.randomUUID().toString();

        store.execute(operation, new Fingerprint(new LinkedHashSet<>(List.of("read", "write"))), () -> mutation(calls))
                .await()
                .indefinitely();
        store.execute(operation, new Fingerprint(new LinkedHashSet<>(List.of("write", "read"))), () -> mutation(calls))
                .await()
                .indefinitely();

        assertEquals(1, calls.get());
    }

    private Uni<Response> mutation(AtomicInteger calls) {
        calls.incrementAndGet();
        return Uni.createFrom().item(Response.created(null).build());
    }

    private record Fingerprint(Set<String> permissions) {}
}

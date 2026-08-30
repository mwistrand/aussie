package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

class RedisAdminMutationStoreTest {

    @Test
    void replaysCompletedResponseWithoutRepeatingMutation() {
        var storeFixture = fixture();
        var firstCall = new AtomicInteger();
        var first = storeFixture
                .store()
                .execute("same-operation", "same-fingerprint", () -> Uni.createFrom()
                        .item(() -> {
                            firstCall.incrementAndGet();
                            return Response.created(URI.create("https://example.test/admin/roles/role-1"))
                                    .type(MediaType.APPLICATION_JSON_TYPE)
                                    .header("ETag", "\"1\"")
                                    .entity(Map.of("id", "role-1"))
                                    .build();
                        }))
                .await()
                .indefinitely();
        var second = storeFixture
                .store()
                .execute("same-operation", "same-fingerprint", () -> Uni.createFrom()
                        .item(Response.serverError().build()))
                .await()
                .atMost(Duration.ofSeconds(1));

        assertEquals(1, firstCall.get());
        assertEquals(first.getStatus(), second.getStatus());
        assertEquals(first.getLocation(), second.getLocation());
        assertEquals(first.getMediaType(), second.getMediaType());
        assertEquals(first.getHeaderString("ETag"), second.getHeaderString("ETag"));
    }

    @Test
    void treatsSetOrderingAsTheSameFingerprint() {
        var store = fixture().store();
        var calls = new AtomicInteger();

        store.execute(
                        "set-order",
                        new Fingerprint(new LinkedHashSet<>(List.of("read", "write"))),
                        () -> mutation(calls))
                .await()
                .indefinitely();
        store.execute(
                        "set-order",
                        new Fingerprint(new LinkedHashSet<>(List.of("write", "read"))),
                        () -> mutation(calls))
                .await()
                .indefinitely();

        assertEquals(1, calls.get());
    }

    @Test
    void keepsReservationWhenMutationFails() {
        var store = fixture().store();
        var calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> store.execute("failed-operation", "same-fingerprint", () -> {
                    calls.incrementAndGet();
                    return Uni.createFrom().failure(new IllegalStateException("mutation failed"));
                })
                .await()
                .indefinitely());
        assertThrows(
                HttpProblem.class, () -> store.execute("failed-operation", "same-fingerprint", () -> mutation(calls))
                        .await()
                        .indefinitely());

        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidStoredRecord() {
        var fixture = fixture();
        fixture.store()
                .execute("invalid-record", "same-fingerprint", () -> mutation(new AtomicInteger()))
                .await()
                .indefinitely();
        fixture.stored().set(fixture.stored().get().replace("\"status\":\"complete\"", "\"status\":\"unknown\""));

        var problem = assertThrows(HttpProblem.class, () -> fixture.store()
                .execute("invalid-record", "same-fingerprint", () -> mutation(new AtomicInteger()))
                .await()
                .indefinitely());

        assertEquals(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), problem.getStatusCode());
    }

    private Uni<Response> mutation(AtomicInteger calls) {
        calls.incrementAndGet();
        return Uni.createFrom().item(Response.ok().build());
    }

    private StoreFixture fixture() {
        var dataSource = mock(ReactiveRedisDataSource.class);
        var values = mock(ReactiveValueCommands.class);
        var audit = mock(ReactiveStreamCommands.class);
        var stored = new AtomicReference<String>();
        when(dataSource.value(String.class, String.class)).thenReturn(values);
        when(dataSource.stream(String.class, String.class, String.class)).thenReturn(audit);
        when(values.setGet(anyString(), anyString(), any())).thenAnswer(invocation -> {
            var pending = invocation.getArgument(1, String.class);
            var existing = stored.get();
            if (existing == null && stored.compareAndSet(null, pending)) {
                return Uni.createFrom().nullItem();
            }
            return Uni.createFrom().item(stored.get());
        });
        when(values.setex(anyString(), any(Long.class), anyString())).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(2, String.class));
            return Uni.createFrom().voidItem();
        });
        return new StoreFixture(new RedisAdminMutationStore(dataSource, new ObjectMapper(), true), stored);
    }

    private record Fingerprint(Set<String> permissions) {}

    private record StoreFixture(RedisAdminMutationStore store, AtomicReference<String> stored) {}
}

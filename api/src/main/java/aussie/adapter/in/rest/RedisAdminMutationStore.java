package aussie.adapter.in.rest;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.stream.ReactiveStreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.util.SecureHash;

/** Durable cross-instance state for administrative retries and audit records. */
@ApplicationScoped
public class RedisAdminMutationStore {

    // ponytail: 10-minute lease; add renewal if an admin mutation can legitimately run longer.
    private static final Duration RETRY_TTL = Duration.ofMinutes(10);
    private static final String OPERATION_PREFIX = "aussie:admin:idempotency:";
    private static final String AUDIT_STREAM = "aussie:admin:audit";

    private final ReactiveValueCommands<String, String> values;
    private final ReactiveStreamCommands<String, String, String> audit;
    private final ObjectMapper mapper;
    private final ObjectMapper fingerprintMapper;
    private final boolean enabled;

    @Inject
    public RedisAdminMutationStore(
            ReactiveRedisDataSource dataSource,
            ObjectMapper mapper,
            @ConfigProperty(name = "aussie.admin-mutations.distributed", defaultValue = "false") boolean enabled) {
        this.values = dataSource.value(String.class, String.class);
        this.audit = dataSource.stream(String.class, String.class, String.class);
        this.mapper = mapper;
        this.fingerprintMapper = fingerprintMapper(mapper);
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    Uni<Response> execute(String operationKey, Object fingerprint, Supplier<Uni<Response>> action) {
        final var key = OPERATION_PREFIX + operationKey;
        final var fingerprintHash = fingerprintHash(fingerprint);
        final var pending = pending(fingerprintHash);
        return values.setGet(key, pending, new SetArgs().nx().ex(RETRY_TTL)).flatMap(existing -> {
            if (existing == null) {
                return runAndStore(key, fingerprintHash, action);
            }
            return replay(existing, fingerprintHash);
        });
    }

    Uni<Void> appendAudit(String actor, String action, String target, String outcome) {
        return audit.xadd(
                        AUDIT_STREAM,
                        new XAddArgs().maxlen(10_000L).nearlyExactTrimming(),
                        Map.of(
                                "at", Instant.now().toString(),
                                "actor", actor,
                                "action", action,
                                "target", target,
                                "outcome", outcome))
                .replaceWithVoid();
    }

    private Uni<Response> runAndStore(String key, String fingerprint, Supplier<Uni<Response>> action) {
        return action.get().flatMap(response -> encode(fingerprint, response)
                .flatMap(encoded ->
                        values.setex(key, RETRY_TTL.toSeconds(), encoded).replaceWith(response)));
    }

    private Uni<Response> replay(String encoded, String fingerprint) {
        try {
            var record = mapper.readTree(encoded);
            var status = record.path("status").asText();
            if (!record.isObject() || (!status.equals("pending") && !status.equals("complete"))) {
                throw new IllegalArgumentException("Invalid idempotency record");
            }
            var storedFingerprint = record.path("fingerprint");
            if (!storedFingerprint.isTextual() || storedFingerprint.asText().length() != 64) {
                throw new IllegalArgumentException("Invalid idempotency fingerprint");
            }
            if (!fingerprint.equals(storedFingerprint.asText())) {
                return Uni.createFrom()
                        .failure(GatewayProblem.conflict("Idempotency-Key was already used for a different request"));
            }
            if (status.equals("pending")) {
                return Uni.createFrom().failure(GatewayProblem.conflict("Idempotency-Key has an unresolved request"));
            }
            if (!record.path("httpStatus").isIntegralNumber()
                    || !record.path("headers").isObject()) {
                throw new IllegalArgumentException("Invalid idempotency response");
            }
            return Uni.createFrom().item(decode(record));
        } catch (Exception invalidRecord) {
            return Uni.createFrom().failure(GatewayProblem.serviceUnavailable("Idempotency store unavailable"));
        }
    }

    private Uni<String> encode(String fingerprint, Response response) {
        return Uni.createFrom().item(() -> {
            var record = mapper.createObjectNode();
            record.put("status", "complete");
            record.put("fingerprint", fingerprint);
            record.put("httpStatus", response.getStatus());
            record.set("entity", response.hasEntity() ? mapper.valueToTree(response.getEntity()) : null);
            record.set("headers", mapper.valueToTree(response.getStringHeaders()));
            try {
                return mapper.writeValueAsString(record);
            } catch (JsonProcessingException serializationFailure) {
                throw new IllegalStateException("Could not serialize idempotent response", serializationFailure);
            }
        });
    }

    private String pending(String fingerprint) {
        var record = mapper.createObjectNode();
        record.put("status", "pending");
        record.put("fingerprint", fingerprint);
        return record.toString();
    }

    private String fingerprintHash(Object fingerprint) {
        try {
            return SecureHash.truncatedSha256(fingerprintMapper.writeValueAsString(fingerprint), 64);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Could not serialize idempotency fingerprint", serializationFailure);
        }
    }

    private static ObjectMapper fingerprintMapper(ObjectMapper mapper) {
        var module = new SimpleModule();
        // ponytail: admin fingerprints only contain Set<String>; generalize if that changes.
        module.addSerializer(Set.class, new SortedStringSetSerializer());
        return mapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .registerModule(module);
    }

    private Response decode(JsonNode record) {
        var httpStatus = record.path("httpStatus").asInt(-1);
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("Invalid idempotency response status");
        }
        var builder = Response.status(httpStatus);
        var entity = record.get("entity");
        if (entity != null && !entity.isNull()) {
            builder.entity(entity);
        }
        record.path("headers").properties().forEach(entry -> {
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(value -> builder.header(entry.getKey(), value.asText()));
            }
        });
        return builder.build();
    }

    private static final class SortedStringSetSerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeStartArray();
            var set = (Set<?>) value;
            var items = set.stream()
                    .map(String.class::cast)
                    .sorted(Comparator.nullsFirst(String::compareTo))
                    .toList();
            for (var item : items) {
                if (item == null) {
                    generator.writeNull();
                } else {
                    generator.writeString(item);
                }
            }
            generator.writeEndArray();
        }
    }
}

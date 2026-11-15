package aussie;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.WithName;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import aussie.adapter.in.http.GatewayCorsConfig;
import aussie.adapter.in.http.SecurityHeadersConfig;
import aussie.adapter.out.http.GatewayConfig;
import aussie.adapter.out.telemetry.TelemetryConfig;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.config.ApiKeyConfig;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.config.BootstrapConfig;
import aussie.core.config.KeyRotationConfig;
import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.RateLimitingConfig;
import aussie.core.config.ResiliencyConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.config.SamplingConfig;
import aussie.core.config.ServiceConfigPubSubConfig;
import aussie.core.config.SessionConfig;
import aussie.core.config.TokenRevocationConfig;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.config.WebSocketConfig;

class ConfigurationMutationTest {

    private static final List<Class<?>> ROOT_MAPPINGS = List.of(
            GatewayCorsConfig.class,
            SecurityHeadersConfig.class,
            GatewayConfig.class,
            TelemetryConfig.class,
            LocalCacheConfig.class,
            ApiKeyConfig.class,
            AuthRateLimitConfig.class,
            BootstrapConfig.class,
            KeyRotationConfig.class,
            OidcConfig.class,
            PkceConfig.class,
            RateLimitingConfig.class,
            ResiliencyConfig.class,
            RouteAuthConfig.class,
            SamplingConfig.class,
            ServiceConfigPubSubConfig.class,
            SessionConfig.class,
            TokenRevocationConfig.class,
            TokenTranslationConfig.class,
            WebSocketConfig.class);

    @TestFactory
    Stream<DynamicTest> everyMappingPropertyChangesItsMappedValue() {
        return ROOT_MAPPINGS.stream()
                .flatMap(root -> leaves(
                                root, root.getAnnotation(ConfigMapping.class).prefix(), List.of())
                        .stream()
                        .map(leaf -> DynamicTest.dynamicTest(leaf.property(), () -> assertMutation(root, leaf))));
    }

    private void assertMutation(Class<?> root, Leaf leaf) throws Exception {
        final var property = leaf.property().replace(".*", ".test");
        final var seed = seedValue(leaf.type());
        final var baseline = mapping(root, values(property, seed)).getConfigMapping(root);
        final var baselineValue = leaf.read(baseline);
        final var mutation = mutationValue(leaf.type(), baselineValue);
        if (mutation.rejects()) {
            assertThrows(ConfigValidationException.class, () -> mapping(root, values(property, mutation)));
            return;
        }
        final var changed = mapping(root, values(property, mutation)).getConfigMapping(root);

        assertNotEquals(
                normalize(baselineValue),
                normalize(leaf.read(changed)),
                "configuration property must change its mapped value: " + property);
    }

    private Map<String, String> values(String property, Mutation mutation) {
        final var values = new HashMap<String, String>();
        if (property.startsWith("aussie.auth.route-auth.providers.test.")) {
            values.put("aussie.auth.route-auth.providers.test.issuer", "https://issuer.example");
            values.put("aussie.auth.route-auth.providers.test.jwks-uri", "https://issuer.example/jwks");
            values.put("aussie.auth.route-auth.providers.test.audiences", "audience");
        }
        values.put(property + mutation.suffix(), mutation.value());
        return values;
    }

    private SmallRyeConfig mapping(Class<?> root, Map<String, String> values) {
        return new SmallRyeConfigBuilder()
                .withSources(new MapConfigSource(values))
                .withMapping(root)
                .withValidateUnknown(false)
                .build();
    }

    private List<Leaf> leaves(Class<?> type, String prefix, List<Step> steps) {
        final var leaves = new ArrayList<Leaf>();
        for (final var method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            final var name = method.isAnnotationPresent(WithName.class)
                    ? method.getAnnotation(WithName.class).value()
                    : camelToKebab(method.getName());
            final var nextPrefix = prefix + "." + name;
            final var nested = nestedType(method.getGenericReturnType());
            if (nested.isPresent()) {
                final var nestedType = nested.get();
                final var nestedPrefix = Map.class.isAssignableFrom(rawType(method.getGenericReturnType()))
                        ? nextPrefix + ".*"
                        : nextPrefix;
                leaves.addAll(leaves(
                        nestedType,
                        nestedPrefix,
                        append(
                                steps,
                                new Step(method, Map.class.isAssignableFrom(rawType(method.getGenericReturnType()))))));
            } else {
                leaves.add(new Leaf(nextPrefix, method.getGenericReturnType(), append(steps, new Step(method, false))));
            }
        }
        return leaves;
    }

    private Optional<Class<?>> nestedType(Type type) {
        final var raw = rawType(type);
        if (raw == Optional.class || raw == List.class || raw == Set.class || raw == Map.class) {
            if (!(type instanceof ParameterizedType parameterized) || raw != Map.class) {
                return Optional.empty();
            }
            type = parameterized.getActualTypeArguments()[1];
        }
        if (type instanceof Class<?> candidate && candidate.isInterface() && candidate != String.class) {
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Class<?> rawType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        return (Class<?>) ((ParameterizedType) type).getRawType();
    }

    private List<Step> append(List<Step> steps, Step step) {
        final var result = new ArrayList<>(steps);
        result.add(step);
        return result;
    }

    private Mutation mutationValue(Type type, Object baseline) {
        final var effectiveType = optionalValueType(type);
        final var effectiveBaseline = baseline instanceof Optional<?> optional ? optional.orElse(null) : baseline;
        final var raw = rawType(effectiveType);
        if (raw == boolean.class || raw == Boolean.class) {
            return new Mutation(Boolean.toString(!Boolean.TRUE.equals(effectiveBaseline)), "", false);
        }
        if (raw == int.class || raw == Integer.class) {
            return new Mutation(Integer.toString(number(effectiveBaseline, 0).intValue() + 1), "", false);
        }
        if (raw == long.class || raw == Long.class) {
            return new Mutation(Long.toString(number(effectiveBaseline, 0L).longValue() + 1), "", false);
        }
        if (raw == double.class || raw == Double.class) {
            return new Mutation(Double.toString(number(effectiveBaseline, 0.0).doubleValue() + 0.25), "", false);
        }
        if (raw == Duration.class) {
            return new Mutation(Objects.equals(Duration.parse("PT7S"), effectiveBaseline) ? "PT8S" : "PT7S", "", false);
        }
        if (raw == Map.class) {
            return new Mutation("user-id", ".subject", false);
        }
        if (raw == String.class) {
            return new Mutation(
                    Objects.equals("__mutated__", effectiveBaseline) ? "__mutated-2__" : "__mutated__", "", false);
        }
        if (raw.isEnum()) {
            return enumMutation(raw, effectiveBaseline);
        }
        if (raw == List.class || raw == Set.class) {
            return new Mutation("__mutated-a__,__mutated-b__", "", false);
        }
        throw new IllegalArgumentException("unsupported config mapping type: " + type);
    }

    private Mutation enumMutation(Class<?> type, Object baseline) {
        for (final var value : type.getEnumConstants()) {
            if (!Objects.equals(value, baseline)) {
                return new Mutation(value.toString(), "", false);
            }
        }
        return new Mutation("__unsupported__", "", true);
    }

    private Mutation seedValue(Type type) {
        final var effectiveType = optionalValueType(type);
        final var raw = rawType(effectiveType);
        if (raw == Map.class) {
            return new Mutation("seed", ".subject", false);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return new Mutation("false", "", false);
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            return new Mutation("1", "", false);
        }
        if (raw == double.class || raw == Double.class) {
            return new Mutation("0.25", "", false);
        }
        if (raw == Duration.class) {
            return new Mutation("PT1S", "", false);
        }
        if (raw.isEnum()) {
            return new Mutation(raw.getEnumConstants()[0].toString(), "", false);
        }
        if (raw == List.class || raw == Set.class) {
            return new Mutation("seed", "", false);
        }
        return new Mutation("seed", "", false);
    }

    private Type optionalValueType(Type type) {
        if (rawType(type) == Optional.class && type instanceof ParameterizedType parameterized) {
            return parameterized.getActualTypeArguments()[0];
        }
        return type;
    }

    private Number number(Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }

    private Object normalize(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private String camelToKebab(String value) {
        return value.replaceAll("(?<!^)([A-Z])", "-$1").toLowerCase();
    }

    private record Mutation(String value, String suffix, boolean rejects) {}

    private record Step(Method method, boolean map) {}

    private record Leaf(String property, Type type, List<Step> steps) {
        Object read(Object root) throws Exception {
            Object value = root;
            for (final var step : steps) {
                value = step.method().invoke(value);
                if (step.map()) {
                    value = ((Map<?, ?>) value).get("test");
                }
            }
            return value;
        }
    }

    private static final class MapConfigSource implements ConfigSource {
        private final Map<String, String> values;

        private MapConfigSource(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Map<String, String> getProperties() {
            return values;
        }

        @Override
        public Set<String> getPropertyNames() {
            return values.keySet();
        }

        @Override
        public String getValue(String propertyName) {
            return values.get(propertyName);
        }

        @Override
        public String getName() {
            return "configuration mutation test";
        }

        @Override
        public int getOrdinal() {
            return 1000;
        }
    }
}

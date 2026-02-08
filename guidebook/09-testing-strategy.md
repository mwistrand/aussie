# Chapter 9: Testing Strategy -- What Staff Engineers Test Differently

A senior engineer writes tests that verify their code works. A staff engineer writes tests that prevent their codebase from decaying. The difference is not effort or coverage percentages. It is the recognition that tests are a communication medium, an architectural enforcement mechanism, and a contract between present and future contributors.

The Aussie API Gateway's test suite is not a collection of regression guards tacked onto features after the fact. It is a deliberate system designed to answer specific questions: Does the architecture remain intact? Do SPI implementors know what they must satisfy? Can a new contributor understand what a class does by reading its tests? Can configuration be overridden without polluting global state?

This chapter examines the testing patterns in Aussie that diverge from what most senior engineers would write, and why.

---

## 1. Hierarchical Test Organization: @Nested + @DisplayName for Cognitive Grouping

Most test classes are flat: a list of methods, loosely grouped by naming convention, in whatever order someone happened to write them. That works for small units. It fails catastrophically for integration tests and resources with multiple dimensions of behavior.

Aussie uses JUnit 5's `@Nested` classes combined with `@DisplayName` annotations to create a hierarchy that reads like a specification.

From `api/src/test/java/aussie/AdminResourceTest.java`, lines 18-20 and 35-37:

```java
@QuarkusTest
@DisplayName("Admin Resource Tests")
class AdminResourceTest {

    @Nested
    @DisplayName("Service Registration")
    class ServiceRegistrationTests {

        @Test
        @DisplayName("Should register a new service")
        void shouldRegisterNewService() {
            // ...
        }

        @Test
        @DisplayName("Should reject service with blocked baseUrl")
        void shouldRejectServiceWithBlockedBaseUrl() {
            // ...
        }

        @Test
        @DisplayName("Should reject service with missing required fields")
        void shouldRejectServiceWithMissingFields() {
            // ...
        }
    }

    @Nested
    @DisplayName("Service Retrieval")
    class ServiceRetrievalTests {

        @Test
        @DisplayName("Should list all registered services")
        void shouldListAllServices() { /* ... */ }

        @Test
        @DisplayName("Should get specific service by ID")
        void shouldGetServiceById() { /* ... */ }

        @Test
        @DisplayName("Should return 404 for non-existent service")
        void shouldReturn404ForNonExistentService() { /* ... */ }
    }

    @Nested
    @DisplayName("Service Deletion")
    class ServiceDeletionTests {
        // ...
    }
}
```

When JUnit renders this hierarchy, it produces output that reads as documentation:

```
Admin Resource Tests
  Service Registration
    Should register a new service
    Should register service with private endpoints
    Should reject service with blocked baseUrl
    Should reject service with missing required fields
    Should inherit defaultAuthRequired=true for endpoints without explicit authRequired
  Service Retrieval
    Should list all registered services
    Should get specific service by ID
    Should return 404 for non-existent service
  Service Deletion
    Should delete existing service
    Should return 404 when deleting non-existent service
```

**What a senior would do instead.** Flat test methods with names like `testRegisterService`, `testRegisterServiceWithBlockedBaseUrl`, `testListServices`. The methods work. But they force the reader to mentally parse each method name to discover the test's intent, and they provide no grouping to distinguish "happy path" from "error handling" from "edge cases."

**Why this matters.** The `@Nested` structure scales. When `AdminResourceTest` adds service update behavior, the author creates a `ServiceUpdateTests` inner class. It slots in naturally. There is no question about where the test belongs. More critically, the `@DisplayName` annotations produce human-readable test reports. When a CI build fails, the failure message reads "Admin Resource Tests > Service Registration > Should reject service with blocked baseUrl" -- not `AdminResourceTest#testRejectServiceWithBlockedBaseUrl`. That is a difference platform engineers notice at 2 AM.

**Trade-offs.** Nested classes add boilerplate. Each `@Nested` class is another indentation level. For a class with three test methods, this is overhead with no payoff. Use this pattern when a test class has five or more tests that naturally group into categories. Below that threshold, a flat structure is fine.

---

## 2. Integration Tests with Real Components + Mocked Boundaries

Unit tests with full mocking verify that your code calls the things you told it to call. Integration tests with everything real verify that the system happens to work in one environment. Neither catches the bugs that emerge from component interactions.

Aussie's `TokenRevocationServiceIntegrationTest` occupies the space between these extremes. It uses **real** bloom filter and cache instances while mocking **only** the external boundary -- the repository and event publisher.

From `api/src/test/java/aussie/core/service/auth/TokenRevocationServiceIntegrationTest.java`, lines 41-110:

```java
@DisplayName("TokenRevocationService Integration")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenRevocationServiceIntegrationTest {

    @Mock
    private TokenRevocationConfig config;
    @Mock
    private TokenRevocationRepository repository;
    @Mock
    private RevocationEventPublisher eventPublisher;
    @Mock
    private Vertx vertx;

    private RevocationBloomFilter bloomFilter;
    private RevocationCache cache;
    private TokenRevocationService service;

    @BeforeEach
    void setUp() throws Exception {
        // Configure revocation as enabled
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.checkThreshold()).thenReturn(Duration.ofSeconds(30));
        // ... additional config setup ...

        // Configure bloom filter
        lenient().when(bloomFilterConfig.enabled()).thenReturn(true);
        lenient().when(bloomFilterConfig.expectedInsertions()).thenReturn(1000);
        lenient().when(bloomFilterConfig.falsePositiveProbability()).thenReturn(0.001);

        // Repository returns empty streams initially
        lenient().when(repository.streamAllRevokedJtis())
                .thenReturn(Multi.createFrom().empty());
        lenient().when(repository.streamAllRevokedUsers())
                .thenReturn(Multi.createFrom().empty());

        // Create real bloom filter and cache
        bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
        cache = new RevocationCache(config);

        // Initialize via reflection (simulating @PostConstruct)
        invokePostConstruct(cache);
        bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));

        // Create service with real bloom filter and cache
        service = new TokenRevocationService(
                config, repository, eventPublisher, bloomFilter, cache);
    }
}
```

The test on lines 128-157 then exercises the **end-to-end revocation flow** through real components:

```java
@Test
@DisplayName("should detect revoked token after revokeToken() is called")
void shouldDetectRevokedTokenAfterRevocation() {
    final var jti = "token-to-revoke";
    final var userId = "user-123";
    final var issuedAt = Instant.now().minus(Duration.ofHours(1));
    final var expiresAt = Instant.now().plus(Duration.ofHours(1));

    when(repository.revoke(anyString(), any()))
            .thenReturn(Uni.createFrom().voidItem());
    when(repository.isRevoked(jti)).thenReturn(Uni.createFrom().item(true));

    // Verify token is initially not revoked (bloom filter says definitely not)
    assertTrue(bloomFilter.definitelyNotRevoked(jti));

    // Revoke the token
    service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

    // Verify bloom filter now says "might be revoked"
    assertFalse(bloomFilter.definitelyNotRevoked(jti));

    // Verify cache has the revocation
    assertTrue(cache.isJtiRevoked(jti).orElse(false));

    // Full service check should return revoked
    final var isRevoked = service.isRevoked(jti, userId, issuedAt, expiresAt)
            .await().atMost(Duration.ofSeconds(1));
    assertTrue(isRevoked, "Service should report token as revoked");
}
```

**What a senior would do instead.** Either mock everything (testing that `service.revokeToken` calls `bloomFilter.addRevokedJti` and `cache.cacheJtiRevocation`) or boot the entire Quarkus application with `@QuarkusTest`. The first approach misses interaction bugs -- what if the bloom filter's internal state is inconsistent after rebuild? The second approach is slow, configuration-dependent, and tests more than intended.

**Why this catches bugs that full-mock tests miss.** Consider what happens if the bloom filter's `addRevokedJti` method updates a filter that `definitelyNotRevoked` does not consult. A fully mocked test cannot detect this because you mocked both methods. The real bloom filter in this test actually maintains internal state, so the assertion on line 147 -- `assertFalse(bloomFilter.definitelyNotRevoked(jti))` -- validates the interaction between two real methods on a real object.

The test on lines 307-320 demonstrates another benefit -- testing the TTL threshold optimization with real components:

```java
@Test
@DisplayName("should skip revocation check for tokens expiring within threshold")
void shouldSkipCheckForSoonExpiringTokens() {
    final var jti = "expiring-soon";
    final var expiresAt = Instant.now().plus(Duration.ofSeconds(15)); // Within 30s threshold

    // Even if we add to bloom filter, should skip check
    bloomFilter.addRevokedJti(jti);

    final var isRevoked = service.isRevoked(jti, userId, issuedAt, expiresAt)
            .await().atMost(Duration.ofSeconds(1));
    assertFalse(isRevoked, "Should skip check for soon-expiring token");
}
```

The config's `checkThreshold` is set to 30 seconds (line 72). When the token expires in 15 seconds, the service short-circuits. This optimization interacts with bloom filter state -- the bloom filter says "might be revoked," but the TTL check preempts it. A mocked bloom filter would not exercise this interaction.

**Trade-offs.** The `invokePostConstruct` reflection hack on lines 112-120 is ugly. It simulates container lifecycle behavior manually. If the `@PostConstruct` method signature changes, the reflection silently does nothing. This is the cost of testing outside the container. The alternative is a `@QuarkusTest` that takes 10x longer to start.

---

## 3. ArchUnit as Architecture Governance

Architecture diagrams lie. They show the intended dependency graph from two sprints ago. The actual dependency graph lives in the compiled bytecode, and it drifts every time someone adds an import statement.

Aussie uses ArchUnit to encode architectural rules as build-breaking tests. If anyone introduces a dependency from core to adapter, the build fails. This is not a code review suggestion or a wiki page -- it is an automated constraint.

From `api/src/test/java/aussie/HexagonalArchitectureTest.java`, lines 16-192:

```java
@DisplayName("Hexagonal Architecture Rules")
class HexagonalArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aussie");
    }

    @Nested
    @DisplayName("Core Layer Rules")
    class CoreLayerRules {

        @Test
        @DisplayName("Core should not depend on adapter")
        void coreShouldNotDependOnAdapter() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("aussie.adapter..");
            rule.check(importedClasses);
        }

        @Test
        @DisplayName("Core should not depend on system")
        void coreShouldNotDependOnSystem() {
            ArchRule rule = noClasses()
                    .that().resideInAPackage("aussie.core..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("aussie.system..");
            rule.check(importedClasses);
        }
    }
}
```

The test suite enforces five separate constraint groups:

1. **Core Layer Rules** (lines 27-56): Core cannot depend on adapter or system packages.
2. **Adapter Layer Rules** (lines 58-74): Adapter cannot depend on system packages.
3. **Common Layer Rules** (lines 76-143): Common cannot depend on core, adapter, or system. (Gracefully skips if the common package is empty.)
4. **Port Interface Rules** (lines 145-160): Outbound ports must be interfaces -- no concrete classes allowed.
5. **Model Rules** (lines 162-191): Models cannot depend on services or ports.

The port interface rule on lines 150-159 is particularly interesting:

```java
@Test
@DisplayName("Outbound ports should only contain interfaces")
void outboundPortsShouldBeInterfaces() {
    ArchRule rule = ArchRuleDefinition.classes()
            .that().resideInAPackage("aussie.core.port.out..")
            .should().beInterfaces();
    rule.check(importedClasses);
}
```

This prevents someone from placing a concrete class in the outbound port package. Without this rule, it is only a matter of time before a utility class or a default implementation leaks into the port layer, coupling it to a specific technology.

**What a senior would do instead.** Rely on code review to enforce layering. Write it in a contributing guide. Hope that reviewers notice the import of `aussie.adapter.out.http.HttpClient` in a core service class. Sometimes they will. Eventually they will not.

**Why this is better.** ArchUnit runs on every build, on every branch, in CI. It does not get tired. It does not miss an import hidden in a 500-line diff. And when it fails, the error message is precise: "Rule 'no classes that reside in a package 'aussie.core..' should depend on classes that reside in a package 'aussie.adapter..'' was violated by [class aussie.core.service.Foo depends on aussie.adapter.bar.Baz]."

**Trade-offs.** ArchUnit adds build time. The `ClassFileImporter` scans all compiled classes in the `aussie` package. For this codebase, that is acceptable. For a monorepo with thousands of classes, consider filtering to specific subpackages. Also, ArchUnit cannot enforce patterns that exist only at the source level (like import ordering or naming conventions) -- it operates on bytecode.

---

## 4. CDI Test Doubles: @Alternative + @Priority for Container Override

Mockito replaces objects in your test. CDI alternatives replace beans in the container. The distinction matters when the component under test does not directly hold a reference to the dependency you want to replace -- when the dependency is injected several layers deep, or when multiple components share it.

From `api/src/test/java/aussie/mock/MockOidcTokenExchangeProvider.java`, lines 23-68:

```java
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockOidcTokenExchangeProvider implements OidcTokenExchangeProvider {

    private static final String NAME = "mock";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE; // Highest priority to override default
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        return Optional.of(HealthCheckResponse.named("oidc-token-exchange-mock")
                .up()
                .withData("provider", NAME)
                .build());
    }

    @Override
    public Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request) {
        OidcTokenExchangeResponse response = new OidcTokenExchangeResponse(
                "mock-access-token-" + System.currentTimeMillis(),
                Optional.empty(),
                Optional.empty(),
                "Bearer",
                3600L,
                Optional.of("openid profile email"),
                Map.of());
        return Uni.createFrom().item(response);
    }
}
```

Three annotations work together. `@Alternative` tells CDI this bean is an alternative implementation. `@Priority(1)` activates it and gives it precedence over the default. `@Mock` (Quarkus-specific) ensures it is only active in test profiles. The class lives in `api/src/test/java/aussie/mock/`, so it is never included in production builds.

**What a senior would do instead.** Use `@InjectMock` on the test field and configure responses per test. That works for simple cases but fails when the OIDC exchange provider is consumed by a registry that discovers implementations via CDI `Instance<OidcTokenExchangeProvider>`. The `@InjectMock` approach does not register the mock as a discoverable CDI bean -- it replaces a single injection point. The `@Alternative` approach replaces the bean everywhere in the container.

**Why this matters for SPIs.** The `OidcTokenExchangeProvider` is a service provider interface. In production, the real provider makes HTTP calls to an identity provider. In tests, every `@QuarkusTest` that exercises authentication flows needs this replaced. A single `@Alternative` bean in the test source set handles all of them. No per-test configuration. No repeated mocking.

**Trade-offs.** The mock is global to all `@QuarkusTest` classes (unless overridden by a test profile). If one test needs different OIDC behavior, you cannot easily customize it -- the `MockOidcTokenExchangeProvider` returns the same canned response for every request. For tests that need varied OIDC responses, you would need either a configurable mock (add a static field) or fall back to per-test `@InjectMock`. The `@Alternative` approach optimizes for the common case at the expense of flexibility.

---

## 5. QuarkusTestProfile for Configuration Override

Test fixtures that modify global state to set up configuration are fragile and order-dependent. Quarkus test profiles provide per-test-class configuration overrides that are scoped, explicit, and isolated.

From `api/src/test/java/aussie/BootstrapIntegrationTest.java`, lines 30-80:

```java
@QuarkusTest
@TestProfile(BootstrapIntegrationTest.BootstrapEnabledProfile.class)
@DisplayName("Bootstrap Integration Tests")
public class BootstrapIntegrationTest {

    static final String TEST_BOOTSTRAP_KEY =
            "test-bootstrap-key-for-integration-tests!";

    public static class BootstrapEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("aussie.bootstrap.enabled", "true"),
                    Map.entry("aussie.bootstrap.key", TEST_BOOTSTRAP_KEY),
                    Map.entry("aussie.bootstrap.ttl", "PT1H"),
                    Map.entry("aussie.auth.dangerous-noop", "false"),
                    Map.entry("aussie.session.enabled", "false"),
                    Map.entry("aussie.session.ttl", "PT8H"),
                    Map.entry("aussie.session.idle-timeout", "PT30M"),
                    Map.entry("aussie.session.sliding-expiration", "true"),
                    Map.entry("aussie.session.id-generation.max-retries", "3"),
                    Map.entry("aussie.session.cookie.name", "aussie_session"),
                    Map.entry("aussie.session.cookie.path", "/"),
                    Map.entry("aussie.session.cookie.secure", "false"),
                    Map.entry("aussie.session.cookie.http-only", "true"),
                    Map.entry("aussie.session.cookie.same-site", "Lax"),
                    Map.entry("aussie.session.storage.provider", "memory"),
                    Map.entry("aussie.session.storage.redis.key-prefix", "aussie:session:"),
                    Map.entry("aussie.session.jws.enabled", "false"),
                    Map.entry("aussie.session.jws.ttl", "PT5M"),
                    Map.entry("aussie.session.jws.issuer", "aussie-gateway"),
                    Map.entry("aussie.session.jws.include-claims", "sub,email,name,roles"),
                    Map.entry("aussie.auth.route-auth.enabled", "false"),
                    Map.entry("aussie.rate-limiting.enabled", "false"),
                    Map.entry("aussie.rate-limiting.redis.enabled", "false"),
                    Map.entry("aussie.auth.rate-limit.enabled", "false"));
        }
    }
}
```

This profile overrides 24 configuration properties. It enables the bootstrap mechanism (`aussie.bootstrap.enabled=true`), disables the dangerous-noop auth mode so real authentication is exercised, disables sessions, disables rate limiting, and provides all the required session configuration that Quarkus's `@ConfigMapping` validation demands even when the feature is disabled.

The test on lines 82-96 then verifies the bootstrap key was created during application startup:

```java
@Test
@DisplayName("should create bootstrap key on startup")
void shouldCreateBootstrapKeyOnStartup() {
    var keys = repository.findAll().await().indefinitely();
    var bootstrapKey = keys.stream()
            .filter(k -> k.name().equals("bootstrap-admin"))
            .findFirst();

    assertTrue(bootstrapKey.isPresent(), "Bootstrap key should exist");
    assertTrue(bootstrapKey.get().permissions().contains(Permission.ALL_VALUE),
            "Bootstrap key should have wildcard permission");
    assertNotNull(bootstrapKey.get().expiresAt(),
            "Bootstrap key should have expiration");
}
```

**What a senior would do instead.** Add properties to `application-test.properties` or use `@TestPropertySource`. The problem: those are global. Every `@QuarkusTest` class shares the same test properties. If `BootstrapIntegrationTest` sets `aussie.auth.dangerous-noop=false` globally, other tests that rely on noop auth break. The alternative is maintaining multiple test property files (`application-bootstrap-test.properties`, `application-auth-test.properties`, etc.) with Quarkus profiles, but that scatters test configuration across the filesystem.

**Why profiles are better.** The configuration is co-located with the test that uses it. When reading `BootstrapIntegrationTest`, you see exactly which properties differ from the default test configuration. There is no need to cross-reference a properties file. And because each test class can declare its own profile, there is no interference between tests.

**Trade-offs.** Quarkus restarts the application when it encounters a new test profile, because profiles can change injected beans and configuration bindings. Tests with different profiles cannot share the same application instance. This adds significant overhead -- each unique profile adds a full application restart. The pragmatic response is to minimize the number of distinct profiles and group tests that need the same overrides under one profile class.

---

## 6. WireMock for Upstream Simulation

An API gateway's core job is proxying requests to upstream services. You cannot test this behavior by mocking the HTTP client -- you need to verify that actual HTTP requests are made with the correct headers, bodies, and methods. WireMock provides a programmable HTTP server that your gateway talks to as if it were a real backend.

From `api/src/test/java/aussie/GatewayIntegrationTest.java`, lines 36-47 and 69-88:

```java
@QuarkusTest
@DisplayName("Gateway Integration Tests")
class GatewayIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    private WireMockServer backendServer;

    @BeforeEach
    void setUp() {
        backendServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort());
        backendServer.start();
    }

    @AfterEach
    void tearDown() {
        if (backendServer != null) {
            backendServer.stop();
        }
        serviceRegistry.getAllServices()
                .await().atMost(java.time.Duration.ofSeconds(5))
                .forEach(s -> serviceRegistry.unregister(s.serviceId())
                        .await().atMost(java.time.Duration.ofSeconds(5)));
    }
}
```

Each test configures WireMock stubs, registers a service pointing to `localhost:{dynamicPort}`, then sends a request through the gateway. The test on lines 69-88 demonstrates the full pattern:

```java
@Test
@DisplayName("Should proxy GET request to registered service")
void shouldProxyGetRequest() {
    // Arrange
    backendServer.stubFor(get(urlEqualTo("/api/users"))
            .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"users\": [\"alice\", \"bob\"]}")));

    registerService("user-service", "/api/users", Set.of("GET"));

    // Act & Assert
    given().when()
            .get("/gateway/api/users")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("users[0]", is("alice"))
            .body("users[1]", is("bob"));

    backendServer.verify(getRequestedFor(urlEqualTo("/api/users")));
}
```

The `backendServer.verify` call on line 88 is critical. It asserts not just that the gateway returned the right response, but that the gateway actually called the backend with the correct URL. Without this verification, a cached response or a hardcoded stub could pass the test.

The header forwarding test on lines 118-138 goes further:

```java
@Test
@DisplayName("Should forward custom headers to backend")
void shouldForwardCustomHeaders() {
    backendServer.stubFor(get(urlEqualTo("/api/data"))
            .willReturn(aResponse().withStatus(200).withBody("OK")));

    registerService("data-service", "/api/data", Set.of("GET"));

    given().header("X-Custom-Header", "custom-value")
            .header("X-Request-Id", "req-12345")
            .when()
            .get("/gateway/api/data")
            .then()
            .statusCode(200);

    backendServer.verify(getRequestedFor(urlEqualTo("/api/data"))
            .withHeader("X-Custom-Header", WireMock.equalTo("custom-value"))
            .withHeader("X-Request-Id", WireMock.equalTo("req-12345")));
}
```

The helper method on lines 236-244 shows how service registration integrates with WireMock:

```java
private void registerService(String serviceId, String path, Set<String> methods) {
    var endpoint = new EndpointConfig(
            path, methods, EndpointVisibility.PUBLIC, java.util.Optional.empty());
    var service = ServiceRegistration.builder(serviceId)
            .displayName(serviceId)
            .baseUrl("http://localhost:" + backendServer.port())
            .endpoints(List.of(endpoint))
            .build();
    serviceRegistry.register(service).await().atMost(java.time.Duration.ofSeconds(5));
}
```

The dynamic port (`backendServer.port()`) ensures no port conflicts between tests. The base URL points to localhost, so the gateway makes real HTTP calls over the loopback interface.

**What a senior would do instead.** Mock the HTTP client used by the gateway and verify that it was called with the expected arguments. That tests the gateway's routing logic but not its HTTP behavior -- content type negotiation, header propagation, error handling, body streaming. WireMock tests the actual wire protocol.

**Trade-offs.** WireMock adds test execution time and introduces non-determinism (port binding failures, slow startup). Each test starts and stops a WireMock server. For a test class with ten tests, that is ten server lifecycles. If WireMock startup is slow, consider using `@BeforeAll`/`@AfterAll` with careful stub cleanup between tests.

---

## 7. Contract Tests for SPIs

When you define a service provider interface, you make a promise: "Implement these methods and everything will work." But how does the implementor know they got it right? A Javadoc comment says "returns true if the token is revoked." The implementor writes something that returns true. But does it return true only for the specific JTI that was revoked? Does it correctly handle tokens issued before a user-level revocation timestamp but not tokens issued after?

Contract tests answer these questions by providing an abstract test suite that SPI implementors extend.

From `api/src/test/java/aussie/spi/TokenRevocationRepositoryContractTest.java`, lines 40-48:

```java
public abstract class TokenRevocationRepositoryContractTest {

    /**
     * Create a fresh instance of the repository under test.
     * Implementations should return a new instance connected to a clean
     * test backend (no leftover data from previous tests).
     */
    protected abstract TokenRevocationRepository createRepository();

    private TokenRevocationRepository repository;

    @BeforeEach
    void setUpContract() {
        repository = createRepository();
    }
}
```

The abstract class defines the contract. Implementors provide one method: `createRepository()`. The contract test does the rest. It verifies five categories of behavior:

**Revoke and check** (lines 57-100):
```java
@Nested
@DisplayName("revoke() and isRevoked()")
class RevokeAndCheckTests {

    @Test
    @DisplayName("revoke() then isRevoked() should return true")
    void revokeAndCheck() {
        var jti = UUID.randomUUID().toString();
        var expiresAt = Instant.now().plus(Duration.ofHours(1));

        repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));
        var revoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));

        assertTrue(revoked, "Token should be revoked after calling revoke()");
    }

    @Test
    @DisplayName("isRevoked() should return false for unknown JTI")
    void unknownJtiNotRevoked() {
        var jti = UUID.randomUUID().toString();
        var revoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));
        assertFalse(revoked, "Unknown JTI should not be revoked");
    }
}
```

**User revocation with temporal semantics** (lines 104-164):
```java
@Test
@DisplayName("revokeAllForUser() affects tokens issued before cutoff")
void userRevocationAffectsOldTokens() {
    var userId = "user-" + UUID.randomUUID();
    var revokedAt = Instant.now();
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revokeAllForUser(userId, revokedAt, expiresAt)
            .await().atMost(Duration.ofSeconds(5));

    var issuedBefore = revokedAt.minus(Duration.ofSeconds(60));
    var revoked = repository.isUserRevoked(userId, issuedBefore)
            .await().atMost(Duration.ofSeconds(5));

    assertTrue(revoked, "Token issued before revocation should be revoked");
}

@Test
@DisplayName("revokeAllForUser() does not affect tokens issued after cutoff")
void userRevocationDoesNotAffectNewTokens() {
    var userId = "user-" + UUID.randomUUID();
    var revokedAt = Instant.now().minus(Duration.ofSeconds(120));
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revokeAllForUser(userId, revokedAt, expiresAt)
            .await().atMost(Duration.ofSeconds(5));

    var issuedAfter = revokedAt.plus(Duration.ofSeconds(60));
    var revoked = repository.isUserRevoked(userId, issuedAfter)
            .await().atMost(Duration.ofSeconds(5));

    assertFalse(revoked, "Token issued after revocation should not be revoked");
}
```

**Isolation between JTI and user revocation** (lines 229-266):
```java
@Test
@DisplayName("JTI revocation should not affect user revocation")
void jtiRevocationIndependentOfUserRevocation() {
    var jti = UUID.randomUUID().toString();
    var userId = "isolation-user-" + UUID.randomUUID();
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));

    var issuedAt = Instant.now().minus(Duration.ofMinutes(5));
    var userRevoked = repository.isUserRevoked(userId, issuedAt)
            .await().atMost(Duration.ofSeconds(5));

    assertFalse(userRevoked, "User should not be affected by JTI revocation");
}
```

The in-memory implementation extends the contract test with minimal code. From `api/src/test/java/aussie/adapter/out/storage/memory/InMemoryTokenRevocationRepositoryContractTest.java`:

```java
class InMemoryTokenRevocationRepositoryContractTest
        extends TokenRevocationRepositoryContractTest {

    private InMemoryTokenRevocationRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.shutdown();
        }
    }

    @Override
    protected TokenRevocationRepository createRepository() {
        if (repository != null) {
            repository.shutdown();
        }
        repository = new InMemoryTokenRevocationRepository();
        return repository;
    }
}
```

Fourteen lines. Zero test methods. The implementor gets 12 contract tests for free.

**What a senior would do instead.** Write a separate test class for each implementation, duplicating the test logic or extracting shared assertions into utility methods. When the contract changes (e.g., a new method is added to the SPI), every implementation's test class must be updated independently.

**Why contract tests are better.** When you add a new test to `TokenRevocationRepositoryContractTest`, every implementation automatically gets tested against it. If a platform team writes a Cassandra implementation and extends the contract test, they know their implementation satisfies the exact same behavioral expectations as the in-memory one. The contract test is the source of truth for what the SPI promises.

**Trade-offs.** Abstract test classes are one of the few places where test inheritance is justified. But it can be overused. A contract test should cover the SPI's behavioral guarantees -- not implementation-specific concerns like connection pooling or retry behavior. Keep the contract test focused on the "what," and let implementation-specific tests handle the "how."

---

## 8. ArgumentCaptor for Complex Assertions

When a method receives a complex argument -- a composite key, an event object, a structured request -- `eq()` and `any()` matchers are insufficient. You need to capture the argument and inspect its structure.

The `RateLimitFilterTest` uses `ArgumentCaptor` extensively to verify the composition of rate limit keys. From `api/src/test/java/aussie/system/filter/RateLimitFilterTest.java`, lines 210-234:

```java
@Test
@DisplayName("should include endpoint path in rate limit key when route is found")
void shouldIncludeEndpointPathInKeyWhenRouteMatchPresent() {
    var service = ServiceRegistration.builder("service-1")
            .displayName("service-1")
            .baseUrl(URI.create("http://localhost:8081"))
            .endpoints(List.of(new EndpointConfig(
                    "/api/users", Set.of("GET", "POST"),
                    EndpointVisibility.PUBLIC, Optional.empty())))
            .build();
    var endpoint = new EndpointConfig(
            "/api/users", Set.of("GET"),
            EndpointVisibility.PUBLIC, Optional.empty());
    var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

    setupRequest("/service-1/api/users", "192.168.1.1");
    when(serviceRegistry.findRoute("/service-1/api/users", "GET"))
            .thenReturn(Optional.of(routeMatch));

    var decision = RateLimitDecision.allow();
    when(rateLimiter.checkAndConsume(any(), any()))
            .thenReturn(Uni.createFrom().item(decision));

    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

    ArgumentCaptor<RateLimitKey> keyCaptor =
            ArgumentCaptor.forClass(RateLimitKey.class);
    verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

    assertEquals(Optional.of("/api/users"),
            keyCaptor.getValue().endpointId());
}
```

The test does not merely verify that `checkAndConsume` was called. It captures the `RateLimitKey` argument and asserts that its `endpointId` field contains the correct value. This catches a subtle class of bugs: the filter might call the rate limiter, but with a key that does not include the endpoint path, causing all endpoints on a service to share a single rate limit bucket.

The client identity extraction tests on lines 338-428 demonstrate multiple ArgumentCaptor assertions for different identity sources:

```java
@Test
@DisplayName("should use API key ID when no session or auth header")
void shouldUseApiKeyIdWhenNoSessionOrAuthHeader() {
    setupRequest("/service-1/api/test", null);
    when(request.getHeader("X-API-Key-ID")).thenReturn("key-456");

    var decision = RateLimitDecision.allow();
    when(rateLimiter.checkAndConsume(any(), any()))
            .thenReturn(Uni.createFrom().item(decision));

    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

    ArgumentCaptor<RateLimitKey> keyCaptor =
            ArgumentCaptor.forClass(RateLimitKey.class);
    verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

    assertEquals("apikey:key-456", keyCaptor.getValue().clientId());
}

@Test
@DisplayName("should use IP address as fallback")
void shouldUseIpAddressAsFallback() {
    setupRequest("/service-1/api/test", "10.0.0.1");

    // ... setup omitted ...

    ArgumentCaptor<RateLimitKey> keyCaptor =
            ArgumentCaptor.forClass(RateLimitKey.class);
    verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

    assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
}
```

Each test verifies a different precedence level in the client identification hierarchy: session cookie, bearer token, API key, IP address. The `ArgumentCaptor` reveals exactly what key was constructed, including the prefix scheme (`session:`, `bearer:`, `apikey:`, `ip:`).

The RFC 7239 Forwarded header parsing tests (lines 430-501) go even further, verifying that complex header parsing produces the correct IP extraction:

```java
@Test
@DisplayName("should parse RFC 7239 Forwarded header with IPv6 address")
void shouldParseForwardedHeaderWithIPv6() {
    setupRequest("/service-1/api/test", null);
    when(request.getHeader("Forwarded"))
            .thenReturn("for=\"[2001:db8:cafe::17]\"");

    // ... rate limiter setup ...

    ArgumentCaptor<RateLimitKey> keyCaptor =
            ArgumentCaptor.forClass(RateLimitKey.class);
    verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

    assertEquals("ip:2001:db8:cafe::17", keyCaptor.getValue().clientId());
}
```

**What a senior would do instead.** Use a custom `ArgumentMatcher` inline: `verify(rateLimiter).checkAndConsume(argThat(k -> k.clientId().equals("ip:10.0.0.1")), any())`. This works for single assertions but becomes unreadable when verifying multiple fields on the captured argument. `ArgumentCaptor` separates the capture from the assertion, making each assertion a clear, independent statement.

**Trade-offs.** `ArgumentCaptor` adds verbosity -- three lines (declare, capture, assert) where a matcher would be one. For single-field checks, a matcher is fine. Reach for `ArgumentCaptor` when you need to assert multiple fields or when the assertion failure message from `assertEquals` is more useful than a matcher's generic "argument(s) are different."

---

## 9. No Shared Test Base Classes

Look at the test directory listing for Aussie. There is no `BaseIntegrationTest`, no `AbstractServiceTest`, no `TestHelper` that every test extends. Each test class is self-contained.

Consider how `AdminResourceTest` (lines 26-33) and `GatewayIntegrationTest` (lines 49-61) both handle cleanup:

```java
// AdminResourceTest
@AfterEach
void tearDown() {
    serviceRegistry.getAllServices()
            .await().atMost(java.time.Duration.ofSeconds(5))
            .forEach(s -> serviceRegistry.unregister(s.serviceId())
                    .await().atMost(java.time.Duration.ofSeconds(5)));
}

// GatewayIntegrationTest
@AfterEach
void tearDown() {
    if (backendServer != null) {
        backendServer.stop();
    }
    serviceRegistry.getAllServices()
            .await().atMost(java.time.Duration.ofSeconds(5))
            .forEach(s -> serviceRegistry.unregister(s.serviceId())
                    .await().atMost(java.time.Duration.ofSeconds(5)));
}
```

The service registry cleanup is duplicated. A refactoring-minded engineer would extract this into a base class. Aussie deliberately does not.

**Why duplication in tests is acceptable.** Test code optimizes for readability and independence, not DRY. When you read `GatewayIntegrationTest`, you see everything it does -- its setup, its teardown, its helpers. You do not need to navigate to a base class, understand its lifecycle hooks, or wonder which inherited methods are being called. When a test fails, the entire context is in one file.

Base test classes create coupling. If `BaseIntegrationTest` changes its `@BeforeEach` method, every test that extends it is affected. If one test needs slightly different setup, you end up with `BaseIntegrationTest`, `BaseIntegrationTestWithAuth`, and `BaseIntegrationTestWithoutRateLimiting` -- a hierarchy that is harder to understand than the duplication it eliminated.

Both `AdminResourceTest` and `RateLimitFilterTest` define their own helper methods:

```java
// AdminResourceTest, lines 314-337
private void registerTestService(String serviceId, String baseUrl) {
    var requestBody = String.format("""
        { "serviceId": "%s", "baseUrl": "%s", ... }
        """, serviceId, baseUrl);
    given().contentType(ContentType.JSON)
            .body(requestBody)
            .when().post("/admin/services")
            .then().statusCode(201);
}

// GatewayIntegrationTest, lines 236-244
private void registerService(String serviceId, String path, Set<String> methods) {
    var endpoint = new EndpointConfig(path, methods,
            EndpointVisibility.PUBLIC, java.util.Optional.empty());
    var service = ServiceRegistration.builder(serviceId)
            .displayName(serviceId)
            .baseUrl("http://localhost:" + backendServer.port())
            .endpoints(List.of(endpoint))
            .build();
    serviceRegistry.register(service).await().atMost(java.time.Duration.ofSeconds(5));
}
```

These helpers serve different purposes. `AdminResourceTest` registers via HTTP (testing the API contract). `GatewayIntegrationTest` registers via the domain service (bypassing the HTTP layer to focus on proxying behavior). Extracting them into a shared helper would force one approach or require parameterization that obscures intent.

**Trade-offs.** When you genuinely have 15 test classes that need identical setup, the duplication becomes a maintenance burden. The exception is contract tests (see Section 7), where the base class is the test -- it defines behavior, not scaffolding. The rule of thumb: inherit test behavior, not test infrastructure.

---

## 10. Testing Reactive Code

Aussie's core is reactive, built on Mutiny's `Uni` and `Multi` types. You cannot synchronously assert on a value that does not exist yet. Every reactive test in the codebase uses the `await().atMost()` pattern to bridge reactive and imperative worlds.

From `TokenRevocationServiceIntegrationTest`, line 143:

```java
service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));
```

From `GatewayIntegrationTest`, line 243:

```java
serviceRegistry.register(service).await().atMost(java.time.Duration.ofSeconds(5));
```

From `RateLimitFilterTest`, line 154:

```java
Response result = filter.filterRequest(requestContext, request)
        .await().atMost(TIMEOUT);
```

The pattern is consistent: call the reactive method, then `.await().atMost(Duration)` to block the test thread until the result arrives or the timeout expires.

**Why you cannot just synchronously assert.** A `Uni<Void>` returned by `revokeToken` represents work that has not happened yet. If you ignore the return value, the revocation may not complete before your assertions run. The `await()` call subscribes to the `Uni` and blocks until it emits, ensuring the side effects have completed.

The timeout is not arbitrary. `RateLimitFilterTest` defines it as a constant on line 59:

```java
private static final Duration TIMEOUT = Duration.ofSeconds(5);
```

Five seconds is generous for an in-memory operation. The timeout exists to prevent tests from hanging indefinitely if a reactive chain never completes (e.g., due to a deadlock or an unsubscribed `Uni`). Without the timeout, a broken reactive chain would cause the test to hang forever, blocking CI.

The `TokenRevocationServiceIntegrationTest` uses a shorter timeout of 1 second for most operations (line 143) but 5 seconds for bloom filter rebuild (line 106):

```java
bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));
```

Rebuilding involves streaming all revoked JTIs from the repository and inserting them into the bloom filter. Even with a mock repository returning an empty stream, the reactive chain has more stages and deserves a larger timeout budget.

**What a senior would do instead.** Use `assertTimeout` from JUnit 5 or `StepVerifier` from Project Reactor. `StepVerifier` is the right tool for Reactor-based projects, but Aussie uses Mutiny, and Mutiny's `await().atMost()` is the idiomatic equivalent. Using `assertTimeout` wraps the entire test in a timeout but does not subscribe to the `Uni` -- you still need `await()`.

**Trade-offs.** The `await().atMost()` pattern blocks the test thread. This is fine for tests but is the exact pattern you must avoid in production code (where blocking an event loop thread causes deadlocks). There is an inherent tension: tests for non-blocking code must block to assert results. Accept this tension. Do not let it tempt you into making your production code synchronous "for testability."

Some tests use `await().indefinitely()` (see `BootstrapIntegrationTest`, line 86):

```java
var keys = repository.findAll().await().indefinitely();
```

This blocks without a timeout. It is appropriate in `@QuarkusTest` contexts where the Quarkus test framework provides its own global timeout, but dangerous in unit tests where nothing prevents an infinite hang. Prefer explicit timeouts in non-container tests.

---

## Synthesis: The Testing Pyramid Is a Lie (Sort Of)

The textbook testing pyramid says "many unit tests, fewer integration tests, even fewer end-to-end tests." Aussie's test suite roughly follows this shape but deviates in important ways:

- **Architecture tests** (ArchUnit) do not fit the pyramid. They are neither unit nor integration tests. They are governance. They should exist in every codebase with more than one layer.
- **Contract tests** exist at the boundary between unit and integration. They test behavior, not implementation, and they are meant to be extended by people who did not write them.
- **Integration tests with real components** (the bloom filter + cache tests) occupy a middle ground that the pyramid does not name. They are not unit tests (multiple real objects), not integration tests (no container), and not end-to-end tests (no HTTP). Call them "component interaction tests."
- **WireMock tests** are the closest to true integration tests, but they mock the upstream, not the downstream. The gateway is real, the backend is fake. This is the opposite of the usual mocking direction.

The common thread across all these patterns is specificity. Each test technique was chosen because it answers a question that other techniques cannot. ArchUnit answers "is the architecture intact?" Contract tests answer "does this implementation satisfy the SPI?" ArgumentCaptor answers "was this complex argument constructed correctly?" No single technique is sufficient. A staff engineer's testing strategy is not about picking one approach -- it is about knowing which tool to reach for when.

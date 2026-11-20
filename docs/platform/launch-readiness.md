# Launch readiness

This checklist is the release gate for describing Aussie as production-ready.
The repository currently keeps that status as experimental until the remaining
deployment, qualification, and release-hardening evidence is complete.

## Security and correctness gates

- [x] CORS policy is bound to the route owner. See the CORS ownership tests in
  `api/src/test/java/aussie/adapter/in/http/`.
- [x] Production sessions fail closed and use the distributed session
  contract. See the session integration and Redis repository tests in
  `api/src/test/java/aussie/` and `api/src/test/java/aussie/adapter/out/storage/redis/`.
- [x] Request limits cover transport framing and application parsing. See
  `api/src/test/java/aussie/RequestValidationTest.java` and the transport
  validation tests.
- [ ] Control-plane mutation responses distinguish durable commit from
  propagation failure at the API boundary. The lower-level
  `api/src/test/java/aussie/core/service/ServiceRegistryTest.java` and
  `api/src/test/java/aussie/adapter/in/rest/RedisAdminMutationStoreTest.java`
  cover internal pending state and replay behavior, but an API-level failure
  test still needs to assert the pending response distinction.
- [ ] CLI authentication configuration is origin-bound and cannot be selected
  from a project configuration file. `cli/internal/config/config_test.go`
  currently proves that local `.aussierc` values can override global
  authentication settings; the trust-boundary fix is still required.
- [x] Sensitive identifiers and operator-controlled reasons are redacted or
  pseudonymized in normal telemetry. See the telemetry redaction tests.
- [x] Outbound DNS resolution is isolated from shared request workers. See
  `api/src/test/java/aussie/adapter/out/http/UpstreamAddressResolverTest.java`.
- [x] The control-plane API and configuration inventories have executable
  compatibility/drift checks. Run `make config-contract` and the API contract
  checks from CI.

## Release gates still required

- [ ] A signed, digest-pinned production image passes the Kubernetes deployment
  qualification with the supported Cassandra and Redis versions. Start with
  `make deployment-contract` and the production deployment runbook.
- [ ] The exact released image passes sustained production-profile load,
  dependency loss/recovery, rolling restart, rollback, and WebSocket evidence.
  The qualification workflow must publish the report with the image digest.
- [ ] The release input and vulnerability policy is complete for all shipped
  artifacts, including the CLI. Run `make workflow-contract`,
  `make container-contract`, and `make vulnerability-contract`.

## Decision rule

Keep the project described as experimental while any release gate above is
unchecked. A release decision may change that label only when the qualification
report names a passing artifact digest and records the dependency versions,
configuration, thresholds, and results.

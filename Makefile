COMPOSE=docker compose

.PHONY: up down restart api api-down demo demo-down otel otel-down migrate storage storage-down test coverage reset demo-deps e2e e2e-logs

up:
	# Bring up cassandra/redis and force-rerun the migration sidecar first so
	# new V*.cql files always apply before any dependent service (api) starts.
	$(MAKE) storage
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

restart: down up

otel:
	$(COMPOSE) up -d --build jaeger prometheus grafana alertmanager
otel-down:
	$(COMPOSE) stop jaeger prometheus grafana alertmanager || true

storage:
	$(COMPOSE) up -d --build cassandra redis
	# Force-recreate the one-shot migration sidecar so new V*.cql files apply
	# on every invocation (otherwise a previously-exited container is left as-is
	# and new migrations silently skip). --no-deps avoids restarting Cassandra.
	$(COMPOSE) up -d --force-recreate --no-deps cassandra-init
storage-down:
	$(COMPOSE) stop cassandra cassandra-init redis || true

# Start everything except the demo app
api:
	$(MAKE) otel
	$(MAKE) storage
	$(COMPOSE) up -d --build api

# Stop everything except the demo app
api-down:
	$(MAKE) otel-down || true
	$(MAKE) storage-down || true
	$(COMPOSE) stop api || true

# Start only the demo app
demo:
	$(COMPOSE) up -d demo

# Stop only the demo app
demo-down:
	$(COMPOSE) stop demo || true

# Run tests: api (Gradle) and cli (Go)
test:
	@echo "Running API tests..."
	cd api && ./gradlew test
	@echo "Running CLI tests..."
	cd cli && go test ./...

# Generate coverage reports for API (JaCoCo) and CLI (Go)
coverage:
	@echo "Running API tests with coverage..."
	cd api && ./gradlew test jacocoTestReport
	@echo "Running CLI tests with coverage..."
	cd cli && go test -coverprofile=coverage.out ./... && go tool cover -html=coverage.out -o coverage.html
	@echo ""
	@echo "Coverage reports:"
	@echo "  API: api/build/reports/jacoco/test/html/index.html"
	@echo "  CLI: cli/coverage.html"

migrate:
	@echo "Running Cassandra migrations..."
	@docker exec -i aussie-cassandra cqlsh -e "describe keyspaces" > /dev/null 2>&1 || \
		(echo "Error: Cassandra is not running. Start it with 'make api' first." && exit 1)
	@for script in api/src/main/resources/db/cassandra/V*.cql; do \
		echo "Applying $$script..."; \
		docker exec -i aussie-cassandra cqlsh < "$$script" || true; \
	done
	@echo "Migrations complete"

# Install demo dependencies. Prefer `npm ci` when a lockfile is present so
# repeated runs match the committed lockfile exactly; fall back to `npm install`
# for first-time setup on a fresh clone without one.
demo-deps:
	@if [ -d demo/node_modules ]; then \
		exit 0; \
	fi; \
	if [ -f demo/package-lock.json ]; then \
		echo "Installing demo deps via npm ci..."; \
		cd demo && npm ci; \
	else \
		echo "demo/package-lock.json absent - falling back to npm install..."; \
		cd demo && npm install; \
	fi

# Run the e2e suite (Aussie + demo + Cassandra + Redis in Testcontainers).
# Requires Docker. Standalone from :check; this is intentionally opt-in because
# container boot is slow. See docs/platform/e2e-tests.md.
e2e: demo-deps
	cd api && ./gradlew e2eTest

# Print the most recent e2e run's container logs.
e2e-logs:
	@latest=$$(ls -1dt api/build/e2e-logs/* 2>/dev/null | head -n1); \
	if [ -z "$$latest" ]; then \
		echo "No e2e logs found under api/build/e2e-logs/"; \
		exit 1; \
	fi; \
	echo "Logs from $$latest:"; \
	for f in api.log demo.log redis.log cassandra.log; do \
		echo; echo "===== $$f ====="; \
		cat "$$latest/$$f" 2>/dev/null || echo "(missing)"; \
	done

reset:
	@echo "Checking Redis and Cassandra are running..."
	@docker exec -i aussie-redis redis-cli ping > /dev/null 2>&1 || (echo "Error: Redis is not running. Start it with 'make api' first." && exit 1)
	@docker exec -i aussie-cassandra cqlsh -e "describe keyspaces" > /dev/null 2>&1 || (echo "Error: Cassandra is not running. Start it with 'make api' first." && exit 1)
	@echo "Flushing Redis data..."
	@docker exec -i aussie-redis redis-cli FLUSHALL || true
	@echo "Dropping Cassandra keyspace 'aussie'..."
	@docker exec -i aussie-cassandra cqlsh -e "DROP KEYSPACE IF EXISTS aussie;" || true
	@echo "Recreating Cassandra schema..."
	@$(MAKE) migrate
	@echo "Cassandra and Redis reset complete."

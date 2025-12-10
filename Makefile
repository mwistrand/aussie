COMPOSE=docker compose

.PHONY: up down restart api api-down demo demo-down test migrate otel otel-down storage storage-down infra infra-down

up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

restart: down up

# Start observability services: Jaeger, Prometheus, Grafana, Alertmanager
otel:
	$(COMPOSE) up -d --build jaeger prometheus grafana alertmanager

otel-down:
	$(COMPOSE) stop jaeger prometheus grafana alertmanager || true

# Start storage services: Cassandra, Cassandra-init, Redis
storage:
	$(COMPOSE) up -d --build cassandra cassandra-init redis

storage-down:
	$(COMPOSE) stop cassandra cassandra-init redis || true

# Start infra: both otel and storage
infra: otel storage

infra-down:
	$(COMPOSE) stop jaeger prometheus grafana alertmanager cassandra cassandra-init redis || true

# Start everything except the demo app
api:
	$(COMPOSE) up -d --build jaeger prometheus grafana alertmanager cassandra cassandra-init redis api

# Stop everything except the demo app
api-down:
	$(COMPOSE) stop jaeger prometheus grafana alertmanager cassandra cassandra-init redis api || true

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

# Run database migrations against running Cassandra
migrate:
	@echo "Running Cassandra migrations..."
	@docker exec -i aussie-cassandra cqlsh -e "describe keyspaces" > /dev/null 2>&1 || \
		(echo "Error: Cassandra is not running. Start it with 'make api' first." && exit 1)
	@for script in api/src/main/resources/db/cassandra/V*.cql; do \
		echo "Applying $$script..."; \
		docker exec -i aussie-cassandra cqlsh < "$$script" || true; \
	done
	@echo "Migrations complete"

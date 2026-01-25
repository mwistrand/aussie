# Aussie Demo Service

A Next.js demo application that showcases integration with the Aussie API Gateway.

## API Endpoints

This demo exposes the following endpoints:

### Public Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Health check endpoint |
| `GET` | `/api/users` | List all users |
| `POST` | `/api/users` | Create a new user |

### Private Endpoints

These endpoints are protected and should only be accessible from internal IPs when routed through Aussie:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/admin/stats` | Get system statistics |
| `POST` | `/api/admin/stats` | Reset statistics |

## Getting Started

### 1. Start the demo server

```bash
npm install
npm run dev
```

The server will be available at http://localhost:3000

### 2. Register with Aussie

Make sure Aussie is running (`cd ../api && ./gradlew quarkusDev`), then register this service:

```bash
# Using the Aussie CLI
cd ../cli
./aussie service register -f ../demo/aussie-service.json

# Or using curl
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d @aussie-service.json
```

### 3. Access through the gateway

Once registered, access the demo service through Aussie:

```bash
# Public endpoints (accessible by anyone)
curl http://localhost:1234/gateway/api/health
curl http://localhost:1234/gateway/api/users

# Create a user
curl -X POST http://localhost:1234/gateway/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Test User", "email": "test@example.com"}'

# Private endpoint (requires allowed IP)
curl http://localhost:1234/gateway/api/admin/stats
```

## Configuration

The `.aussierc` file in this directory configures the Aussie CLI to connect to the local gateway:

```toml
host = "http://localhost:1234"
```

## Service Registration

The `aussie-service.json` file defines how this service is registered with Aussie:

```json
{
  "serviceId": "demo-service",
  "displayName": "Demo Service",
  "baseUrl": "http://localhost:3000",
  "endpoints": [
    { "path": "/api/health", "methods": ["GET"], "visibility": "PUBLIC" },
    { "path": "/api/users", "methods": ["GET", "POST"], "visibility": "PUBLIC" },
    { "path": "/api/admin/stats", "methods": ["GET", "POST"], "visibility": "PRIVATE" }
  ]
}
```

---

# Aussie Gateway Demo

This guide walks through practical examples of both routing strategies.

## Prerequisites

1. Start Aussie gateway:
   ```bash
   cd /path/to/aussie/api
   ./gradlew quarkusDev
   ```

2. Start mock backend services (using any HTTP server, e.g., `json-server`, `httpbin`, or simple Node/Python servers)

For this demo, we'll assume:
- User service running on `http://localhost:3001`
- Order service running on `http://localhost:3002`
- Inventory service running on `http://localhost:3003`

---

## Demo 1: Pass-Through Routing

Pass-through routing is ideal when clients know which service they need and you want simple, direct access.

### Setup

Register three services (no endpoints needed for pass-through):

```bash
# User service
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "users",
    "displayName": "User Service",
    "baseUrl": "http://localhost:3001"
  }'

# Order service
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "orders",
    "displayName": "Order Service",
    "baseUrl": "http://localhost:3002"
  }'

# Inventory service
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "inventory",
    "displayName": "Inventory Service",
    "baseUrl": "http://localhost:3003"
  }'
```

### Usage

Access services by their ID in the URL:

```bash
# User service endpoints
curl http://localhost:1234/users/api/users
curl http://localhost:1234/users/api/users/123
curl -X POST http://localhost:1234/users/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice", "email": "alice@example.com"}'

# Order service endpoints
curl http://localhost:1234/orders/api/orders
curl http://localhost:1234/orders/api/orders/456
curl http://localhost:1234/orders/api/orders?userId=123

# Inventory service endpoints
curl http://localhost:1234/inventory/api/products
curl http://localhost:1234/inventory/api/products/SKU-001/stock
```

### Key Points

1. **Service ID = first path segment**: `/users/...` routes to service with ID "users"
2. **All paths forwarded**: Any path after the service ID goes to the backend
3. **No endpoint registration**: Just register the service, all paths work automatically

---

## Demo 2: Gateway Routing

Gateway routing is ideal when you want a unified API namespace where clients don't need to know about backend services.

### Setup

Register services with explicit endpoint patterns:

```bash
# User service with specific endpoints
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "user-service",
    "displayName": "User Service",
    "baseUrl": "http://localhost:3001",
    "endpoints": [
      {"path": "/api/users", "methods": ["GET", "POST"], "visibility": "PUBLIC"},
      {"path": "/api/users/{id}", "methods": ["GET", "PUT", "DELETE"], "visibility": "PUBLIC"},
      {"path": "/api/users/{id}/profile", "methods": ["GET", "PUT"], "visibility": "PUBLIC"}
    ]
  }'

# Order service with specific endpoints
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "order-service",
    "displayName": "Order Service",
    "baseUrl": "http://localhost:3002",
    "endpoints": [
      {"path": "/api/orders", "methods": ["GET", "POST"], "visibility": "PUBLIC"},
      {"path": "/api/orders/{id}", "methods": ["GET"], "visibility": "PUBLIC"},
      {"path": "/api/users/{userId}/orders", "methods": ["GET"], "visibility": "PUBLIC"}
    ]
  }'

# Inventory service with wildcard endpoints
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "inventory-service",
    "displayName": "Inventory Service",
    "baseUrl": "http://localhost:3003",
    "endpoints": [
      {"path": "/api/products/**", "methods": ["*"], "visibility": "PUBLIC"},
      {"path": "/api/inventory/**", "methods": ["*"], "visibility": "PRIVATE"}
    ]
  }'
```

### Usage

Access all services through a unified `/gateway` namespace:

```bash
# User endpoints (routed to user-service)
curl http://localhost:1234/gateway/api/users
curl http://localhost:1234/gateway/api/users/123
curl http://localhost:1234/gateway/api/users/123/profile

# Order endpoints (routed to order-service)
curl http://localhost:1234/gateway/api/orders
curl http://localhost:1234/gateway/api/orders/456
curl http://localhost:1234/gateway/api/users/123/orders  # User's orders!

# Inventory endpoints (routed to inventory-service)
curl http://localhost:1234/gateway/api/products
curl http://localhost:1234/gateway/api/products/SKU-001
curl http://localhost:1234/gateway/api/products/SKU-001/stock
```

### Key Points

1. **Unified namespace**: All requests go through `/gateway/...`
2. **Path-based routing**: Gateway matches request path to registered endpoints
3. **Multiple services, same paths**: `/api/users/{userId}/orders` routes to order-service, not user-service
4. **Wildcards**: `/api/products/**` matches any subpath

---

## Demo 3: Path Rewriting

Gateway routing supports path rewriting for API versioning or path transformation.

### Setup

```bash
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "user-service-v2",
    "displayName": "User Service v2",
    "baseUrl": "http://localhost:3001",
    "endpoints": [
      {
        "path": "/api/v2/users",
        "methods": ["GET", "POST"],
        "visibility": "PUBLIC",
        "pathRewrite": "/users"
      },
      {
        "path": "/api/v2/users/{id}",
        "methods": ["GET", "PUT", "DELETE"],
        "visibility": "PUBLIC",
        "pathRewrite": "/users/{id}"
      }
    ]
  }'
```

### Usage

```bash
# Client calls v2 API
curl http://localhost:1234/gateway/api/v2/users/123

# Gateway rewrites and forwards to backend
# → GET http://localhost:3001/users/123
```

---

## Demo 4: Visibility and Access Control

Control which endpoints are public vs private.

### Setup

```bash
curl -X POST http://localhost:1234/admin/services \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "admin-service",
    "displayName": "Admin Service",
    "baseUrl": "http://localhost:3004",
    "defaultVisibility": "PRIVATE",
    "endpoints": [
      {"path": "/api/health", "methods": ["GET"], "visibility": "PUBLIC"},
      {"path": "/api/admin/**", "methods": ["*"], "visibility": "PRIVATE"}
    ],
    "accessConfig": {
      "allowedIps": ["10.0.0.0/8", "192.168.1.0/24"],
      "allowedDomains": ["admin.internal.example.com"]
    }
  }'
```

### Behavior

```bash
# Public endpoint - accessible from anywhere
curl http://localhost:1234/admin-service/api/health  # 200 OK

# Private endpoint - blocked unless from allowed IP/domain
curl http://localhost:1234/admin-service/api/admin/users  # 404 (blocked)

# From allowed IP (e.g., 10.0.0.50)
curl http://localhost:1234/admin-service/api/admin/users  # 200 OK
```

---

## Choosing the Right Strategy

| Scenario | Recommended | Why |
|----------|-------------|-----|
| Microservices with clear boundaries | Pass-Through | Simple, explicit service addressing |
| Public API with unified namespace | Gateway | Hide internal service topology |
| API versioning (`/v1`, `/v2`) | Gateway | Path rewriting support |
| Service mesh / internal routing | Pass-Through | Direct, low overhead |
| Multi-tenant with service isolation | Pass-Through | Clear tenant→service mapping |
| BFF (Backend for Frontend) | Gateway | Aggregate multiple services |

---

## Troubleshooting

### "Service not found" on pass-through

```bash
# Check registered services
curl http://localhost:1234/admin/services

# Verify serviceId matches URL
# URL: /user-service/... requires serviceId: "user-service"
```

### "Not found" on gateway routing

```bash
# Check registered endpoints
curl http://localhost:1234/admin/services/user-service

# Verify endpoint path and method match
# Endpoint: {"path": "/api/users", "methods": ["GET"]}
# Request must match: GET /gateway/api/users
```

### Reserved paths

These paths cannot be used as service IDs:
- `admin` - Admin API
- `gateway` - Gateway routing
- `q` - Quarkus endpoints (health, metrics)

```bash
# This will fail
curl -X POST http://localhost:1234/admin/services \
  -d '{"serviceId": "admin", ...}'  # Reserved!
```

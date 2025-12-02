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
./aussie register -f ../demo/aussie-service.json

# Or using curl
curl -X POST http://localhost:8080/admin/services \
  -H "Content-Type: application/json" \
  -d @aussie-service.json
```

### 3. Access through the gateway

Once registered, access the demo service through Aussie:

```bash
# Public endpoints (accessible by anyone)
curl http://localhost:8080/gateway/api/health
curl http://localhost:8080/gateway/api/users

# Create a user
curl -X POST http://localhost:8080/gateway/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "Test User", "email": "test@example.com"}'

# Private endpoint (requires allowed IP)
curl http://localhost:8080/gateway/api/admin/stats
```

## Configuration

The `.aussierc` file in this directory configures the Aussie CLI to connect to the local gateway:

```toml
host = "http://localhost:8080"
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

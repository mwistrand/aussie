# Aussie API Gateway

<p align="center">
  <img src="static/img/aussie.svg" alt="Aussie Logo" width="150">
</p>

Aussie is an experimental API Gateway built with Quarkus, designed for exploring modern gateway patterns and proxy architectures.

## Overview

Aussie is a lightweight API gateway for microservices. It provides two routing strategies to fit different architectural needs:

- **Pass-Through Routing** (`/{serviceId}/{path}`) - Routes requests directly to a service by its ID
- **Gateway Routing** (`/gateway/{path}`) - Routes requests based on registered endpoint patterns, allowing multiple services to share a unified API namespace

## Documentation

| Guide | Audience | Description |
|-------|----------|-------------|
| [Platform Guide](doc/PLATFORM.md) | Platform teams | Deploying, configuring, and operating Aussie |
| [Consumer Guide](doc/CONSUMER.md) | App developers | Onboarding services to Aussie |

## Quick Start

### Start the Gateway
```bash
make up
```
The gateway will be available at http://localhost:1234 with the Demo UI at http://localhost:8080.

### Build the CLI
```bash
cd cli
go build -o ../aussie
```

### Register a Service
```bash
./aussie register -f path/to/onboarding/config.json
```

### Make Requests
```bash
# Pass-through routing (service ID in URL)
curl http://localhost:8080/my-service/api/endpoint

# Gateway routing (unified namespace)
curl http://localhost:8080/gateway/api/endpoint
```

## Project Structure
```
aussie/
├── api/          # Quarkus REST API (Java 21)
├── cli/          # Command-line interface (Go)
├── demo/         # Demo application (Next.js)
└── doc/          # Documentation
    ├── PLATFORM.md   # Platform team guide
    └── CONSUMER.md   # App developer guide
```

## Development

### Prerequisites
- Java 21
- Go 1.21+ (for CLI)
- Node.js 18+ (for demo)

### Building

```bash
# Build the API
cd api
./gradlew build

# Build the CLI
cd cli
go build -o aussie

# Build native executable
cd api
./gradlew build -Dquarkus.native.enabled=true
```

### Running Tests
```bash
cd api
./gradlew test

# Run a specific test class
./gradlew test --tests "aussie.AdminResourceTest"

# Run a specific test method
./gradlew test --tests "aussie.AdminResourceTest.testMethodName"
```

### Dev Mode
```bash
cd api
./gradlew quarkusDev
```
This enables live reload - changes to Java files are automatically compiled and deployed.

### Demo Application

A Next.js demo application is included to showcase integration with Aussie. See [demo/README.md](demo/README.md) for setup instructions and examples of both routing strategies.

```bash
# Start the demo
cd demo
npm install
npm run dev

# Register with Aussie
cd ..
./aussie register -f demo/aussie-service.json
```

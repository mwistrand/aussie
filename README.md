# Aussie API Gateway

<p align="center">
  <img src="static/img/aussie.svg" alt="Aussie Logo" width="150">
</p>


Aussie is an experimental API Gateway built with Quarkus, designed for exploring modern gateway patterns and proxy architectures.

## Project Structure

```
aussie/
├── api/          # Quarkus REST API (Java 21)
├── cli/          # Command-line interface (Go)
└── demo/         # Demo application (Next.js)
```

## API

The core API gateway built with [Quarkus](https://quarkus.io/), the Supersonic Subatomic Java Framework.

### Running in dev mode

```shell
cd api
./gradlew quarkusDev
```

> **Note:** Quarkus Dev UI is available at http://localhost:8080/q/dev/

### Building

```shell
cd api
./gradlew build
```

### Native executable

```shell
cd api
./gradlew build -Dquarkus.native.enabled=true
```

## CLI

A Go-based command-line interface for interacting with the Aussie API gateway.

### Building

```shell
cd cli
go build -o aussie .
```

### Usage

```shell
./aussie --help
./aussie version
```

## Demo

A Next.js application for testing and demonstrating the Aussie API gateway capabilities.

### Running

```shell
cd demo
npm install
npm run dev
```

The demo app will be available at http://localhost:3000

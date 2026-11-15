#!/usr/bin/env bash
set -euo pipefail

# Disposable qualification for the repository-owned Kubernetes deployment.
# The image must be a released digest; this is intentionally not a build path.

: "${AUSSIE_QUALIFICATION_IMAGE:?set AUSSIE_QUALIFICATION_IMAGE to a full @sha256 image reference}"

if [[ ! "$AUSSIE_QUALIFICATION_IMAGE" =~ ^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "AUSSIE_QUALIFICATION_IMAGE must be a full image@sha256:<64 hex> reference" >&2
  exit 1
fi

for command in docker kind kubectl openssl; do
  command -v "$command" >/dev/null || {
    echo "$command is required for Kubernetes qualification" >&2
    exit 1
  }
done

cluster="aussie-qualification-$$"
namespace="aussie-qualification"
keep_cluster="${AUSSIE_QUALIFICATION_KEEP_CLUSTER:-false}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cleanup() {
  status=$?
  if [[ "$status" -ne 0 ]]; then
    kubectl --context "kind-$cluster" -n "$namespace" get pods -o wide 2>/dev/null || true
    kubectl --context "kind-$cluster" -n "$namespace" get events --sort-by=.lastTimestamp 2>/dev/null || true
  fi
  if [[ "$keep_cluster" != "true" ]]; then
    kind delete cluster --name "$cluster" >/dev/null 2>&1 || true
  else
    echo "Keeping kind cluster $cluster (context kind-$cluster)"
  fi
  exit "$status"
}
trap cleanup EXIT

kind create cluster --name "$cluster" --config="$repo_root/tools/kind-qualification.yaml"

kubectl --context "kind-$cluster" create namespace "$namespace"
kubectl --context "kind-$cluster" label namespace "$namespace" \
  aussie.network/ingress=true \
  aussie.network/cassandra=true \
  aussie.network/redis=true

zone=0
while IFS= read -r node; do
  kubectl --context "kind-$cluster" label node "$node" \
    "topology.kubernetes.io/zone=qualification-zone-$zone" --overwrite
  zone=$((zone + 1))
done < <(kubectl --context "kind-$cluster" get nodes -o name)

kubectl --context "kind-$cluster" -n "$namespace" create secret generic aussie-production-secrets \
  --from-literal=CASSANDRA_USERNAME= \
  --from-literal=CASSANDRA_PASSWORD= \
  --from-literal=REDIS_PASSWORD= \
  --from-literal=AUTH_ENCRYPTION_KEY="$(openssl rand -base64 32)"

kubectl --context "kind-$cluster" -n "$namespace" apply -f - <<'YAML'
apiVersion: v1
kind: Service
metadata:
  name: cassandra
  labels:
    app: cassandra
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  selector:
    app: cassandra
  ports:
    - name: cql
      port: 9042
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: cassandra
spec:
  serviceName: cassandra
  replicas: 3
  selector:
    matchLabels:
      app: cassandra
  template:
    metadata:
      labels:
        app: cassandra
    spec:
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: cassandra
      containers:
        - name: cassandra
          image: cassandra:4.1.4
          env:
            - name: CASSANDRA_CLUSTER_NAME
              value: aussie-qualification
            - name: CASSANDRA_SEEDS
              value: cassandra-0.cassandra
            - name: CASSANDRA_DC
              value: datacenter1
            - name: CASSANDRA_RACK
              value: rack1
            - name: HEAP_NEWSIZE
              value: 128M
            - name: MAX_HEAP_SIZE
              value: 512M
          ports:
            - name: cql
              containerPort: 9042
          readinessProbe:
            exec:
              command: ["sh", "-c", "cqlsh -e 'describe cluster' >/dev/null"]
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 30
          volumeMounts:
            - name: data
              mountPath: /var/lib/cassandra
      volumes:
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  labels:
    app: redis
spec:
  selector:
    app: redis
  ports:
    - name: redis
      port: 6379
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
        - name: redis
          image: redis:7.2.5-alpine
          ports:
            - name: redis
              containerPort: 6379
          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
YAML

context="--context kind-$cluster"
kubectl $context -n "$namespace" rollout status statefulset/cassandra --timeout=15m
kubectl $context -n "$namespace" rollout status deployment/redis --timeout=5m

kubectl $context -n "$namespace" exec cassandra-0 -- nodetool status \
  | awk '/^UN / { nodes++ } END { if (nodes != 3) exit 1 }'

kubectl $context -n "$namespace" run cassandra-admin \
  --image=cassandra:4.1.4 --restart=Never --rm -i --command -- \
  cqlsh cassandra -e "CREATE KEYSPACE aussie WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 2};"

# Render exactly the repository base, changing only the disposable image and
# dependency endpoints needed by this cluster.
kubectl kustomize "$repo_root/deploy/kubernetes" \
  | sed \
      -e "s|ghcr.io/mwistrand/aussie@sha256:REPLACE_WITH_SIGNED_RELEASE_DIGEST|$AUSSIE_QUALIFICATION_IMAGE|g" \
      -e 's|cassandra.example.internal:9042|cassandra:9042|g' \
      -e 's|redis.example.internal:6379|redis:6379|g' \
      -e 's|CASSANDRA_RUN_MIGRATIONS: "false"|CASSANDRA_RUN_MIGRATIONS: "true"|g' \
  | kubectl $context -n "$namespace" apply -f -

kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=15m

kubectl $context -n "$namespace" run qualification-traffic \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false --command -- \
  sh -ec '
    if ! curl --fail --silent http://aussie:8080/q/health/ready >/dev/null; then
      exit 1
    fi
    touch /tmp/first-success
    while [ ! -f /tmp/strict ]; do
      curl --fail --silent http://aussie:8080/q/health/ready >/dev/null || true
      sleep 1
    done
    for i in $(seq 1 120); do
      if ! curl --fail --silent http://aussie:8080/q/health/ready >/dev/null; then
        exit 1
      fi
      sleep 1
    done
  '

wait_for_first_probe() {
  for _ in $(seq 1 120); do
    phase="$(kubectl $context -n "$namespace" get pod qualification-traffic \
      -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in
      Running)
        if kubectl $context -n "$namespace" exec qualification-traffic -- test -f /tmp/first-success 2>/dev/null; then
          return 0
        fi
        ;;
      Failed|Succeeded)
        kubectl $context -n "$namespace" logs qualification-traffic 2>/dev/null || true
        echo "traffic probe ended before pod-loss test began" >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "timed out waiting for the first successful readiness probe" >&2
  return 1
}

wait_for_first_probe

wait_for_gateway_ready() {
  for _ in $(seq 1 120); do
    if kubectl $context -n "$namespace" exec qualification-traffic -- \
      sh -ec 'curl --fail --silent http://aussie:8080/q/health/ready >/dev/null' 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "timed out waiting for gateway readiness" >&2
  return 1
}

wait_for_gateway_unready() {
  for _ in $(seq 1 120); do
    if ! kubectl $context -n "$namespace" exec qualification-traffic -- \
      sh -ec 'curl --fail --silent http://aussie:8080/q/health/ready >/dev/null' 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "gateway remained ready after Redis loss" >&2
  return 1
}

kubectl $context -n "$namespace" scale deployment/redis --replicas=0
wait_for_gateway_unready
kubectl $context -n "$namespace" scale deployment/redis --replicas=1
kubectl $context -n "$namespace" rollout status deployment/redis --timeout=5m
wait_for_gateway_ready
kubectl $context -n "$namespace" exec qualification-traffic -- touch /tmp/strict

victim="$(kubectl $context -n "$namespace" get pods -l app.kubernetes.io/name=aussie \
  -o jsonpath='{.items[0].metadata.name}')"
test -n "$victim"
kubectl $context -n "$namespace" delete pod "$victim" --wait=false

wait_for_traffic() {
  for _ in $(seq 1 180); do
    phase="$(kubectl $context -n "$namespace" get pod qualification-traffic \
      -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in
      Succeeded) return 0 ;;
      Failed) kubectl $context -n "$namespace" logs qualification-traffic; return 1 ;;
    esac
    sleep 1
  done
  echo "timed out waiting for traffic probe" >&2
  return 1
}

wait_for_traffic
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=5m
ready_replicas="$(kubectl $context -n "$namespace" get deployment aussie -o jsonpath='{.status.readyReplicas}')"
test "$ready_replicas" = 3

echo "Kubernetes qualification passed: three-worker kind cluster, production readiness, Redis recovery, and pod-loss traffic recovery"

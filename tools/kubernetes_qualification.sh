#!/usr/bin/env bash
set -euo pipefail

# Disposable qualification for the repository-owned Kubernetes deployment.
# The image must be a released digest; this is intentionally not a build path.

qualification_image="${AUSSIE_QUALIFICATION_IMAGE:-not provided}"
load_seconds="${AUSSIE_QUALIFICATION_LOAD_SECONDS:-30}"
load_interval="${AUSSIE_QUALIFICATION_LOAD_INTERVAL_SECONDS:-0.1}"
load_p95_max="${AUSSIE_QUALIFICATION_LOAD_P95_MAX_SECONDS:-1}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_file="${AUSSIE_QUALIFICATION_REPORT:-$repo_root/api/build/qualification/kubernetes-qualification-$(date -u +%Y%m%dT%H%M%SZ)-$$.md}"

qualification_stage="initialization"
qualification_status="failed"
load_output="not run"
redis_recovery="not run"
cassandra_recovery="not run"
pod_recovery="not run"
cluster=""
namespace="aussie-qualification"
keep_cluster="${AUSSIE_QUALIFICATION_KEEP_CLUSTER:-false}"

write_report() {
  local exit_status="$1"
  local report_parent
  report_parent="$(dirname "$report_file")"
  if [[ "$exit_status" -eq 0 ]]; then
    qualification_status="passed"
  fi
  if ! mkdir -p "$report_parent"; then
    echo "Unable to create qualification report directory: $report_parent" >&2
    return 1
  fi
  if ! {
    echo "# Kubernetes qualification report"
    echo
    echo "- Result: $qualification_status"
    echo "- Generated (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "- Git commit: $(git -C "$repo_root" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "- Released image: $qualification_image"
    echo "- Qualification stage: $qualification_stage"
    echo "- Cluster: ${cluster:-not created}"
    echo "- Namespace: $namespace"
    echo "- Production profile: prod"
    echo "- Cassandra image: cassandra:4.1.4"
    echo "- Redis image: redis:7.2.5-alpine"
    echo "- Load image: curlimages/curl:8.10.1"
    echo "- Load duration: ${load_seconds}s"
    echo "- Load interval: ${load_interval}s"
    echo "- Load p95 limit: ${load_p95_max}s"
    echo "- Redis recovery: $redis_recovery"
    echo "- Cassandra recovery: $cassandra_recovery"
    echo "- Gateway pod-loss recovery: $pod_recovery"
    echo
    echo "## Sustained readiness load"
    echo
    echo '```text'
    echo "$load_output"
    echo '```'
  } > "$report_file"; then
    echo "Unable to write qualification report: $report_file" >&2
    return 1
  fi
  echo "Qualification report: $report_file"
}

cleanup() {
  local status=$?
  local report_status=0
  [[ "$redis_recovery" == "running" ]] && redis_recovery="failed"
  [[ "$cassandra_recovery" == "running" ]] && cassandra_recovery="failed"
  [[ "$pod_recovery" == "running" ]] && pod_recovery="failed"
  if write_report "$status"; then
    :
  else
    report_status=$?
  fi
  if [[ "$status" -ne 0 && -n "$cluster" ]] && command -v kubectl >/dev/null 2>&1; then
    kubectl --context "kind-$cluster" -n "$namespace" get pods -o wide 2>/dev/null || true
    kubectl --context "kind-$cluster" -n "$namespace" get events --sort-by=.lastTimestamp 2>/dev/null || true
  fi
  if [[ -n "$cluster" && "$keep_cluster" != "true" ]] && command -v kind >/dev/null 2>&1; then
    kind delete cluster --name "$cluster" >/dev/null 2>&1 || true
  elif [[ -n "$cluster" && "$keep_cluster" == "true" ]]; then
    echo "Keeping kind cluster $cluster (context kind-$cluster)"
  fi
  if [[ "$status" -eq 0 && "$report_status" -ne 0 ]]; then
    status="$report_status"
  fi
  exit "$status"
}
trap cleanup EXIT

if [[ -z "${AUSSIE_QUALIFICATION_IMAGE:-}" ]]; then
  echo "set AUSSIE_QUALIFICATION_IMAGE to a full @sha256 image reference" >&2
  exit 1
fi
case "$load_seconds" in
  ''|*[!0-9]*) echo "AUSSIE_QUALIFICATION_LOAD_SECONDS must be a positive integer" >&2; exit 1 ;;
esac
case "$load_interval" in
  ''|*[!0-9.]*|.*|*.*.*) echo "AUSSIE_QUALIFICATION_LOAD_INTERVAL_SECONDS must be a positive decimal" >&2; exit 1 ;;
esac
case "$load_p95_max" in
  ''|*[!0-9.]*|.*|*.*.*) echo "AUSSIE_QUALIFICATION_LOAD_P95_MAX_SECONDS must be a positive decimal" >&2; exit 1 ;;
esac
test "$load_seconds" -gt 0
awk -v value="$load_interval" 'BEGIN { exit !(value > 0) }'
awk -v value="$load_p95_max" 'BEGIN { exit !(value > 0) }'

if [[ ! "$qualification_image" =~ ^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "AUSSIE_QUALIFICATION_IMAGE must be a full image@sha256:<64 hex> reference" >&2
  exit 1
fi

for command in cosign docker kind kubectl openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required for Kubernetes qualification" >&2
    exit 1
  }
done

cosign verify \
  --certificate-identity-regexp '^https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/.+$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$qualification_image" >/dev/null

cluster="aussie-qualification-$$"
qualification_stage="creating kind cluster"
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
          command: ["sh", "-ec"]
          args:
            - |
              gosu cassandra sh -ec '
                cassandra -f &
                child=$!
                echo "$child" > /tmp/cassandra.pid
                wait "$child"
              '
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

qualification_stage="checking Cassandra cluster"
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

qualification_stage="waiting for gateway readiness"
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

qualification_stage="sustained readiness load"
kubectl $context -n "$namespace" run qualification-load \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false \
  --env="LOAD_SECONDS=$load_seconds" \
  --env="LOAD_INTERVAL_SECONDS=$load_interval" \
  --env="LOAD_P95_MAX_SECONDS=$load_p95_max" \
  --command -- sh -ec '
    : > /tmp/results
    pids=""
    deadline=$(( $(date +%s) + LOAD_SECONDS ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      (
        if result="$(curl --max-time 5 --silent --show-error --output /dev/null \
          --write-out "%{http_code} %{time_total}" http://aussie:8080/q/health/ready 2>/dev/null)"; then
          :
        else
          result="000 0"
        fi
        printf "%s\n" "$result" >> /tmp/results
      ) &
      pids="$pids $!"
      sleep "$LOAD_INTERVAL_SECONDS"
    done
    for pid in $pids; do
      wait "$pid" || true
    done

    set -- $(awk "
      { total++ }
      \$1 == 200 { success++ }
      END { print total + 0, success + 0, (total - success) + 0 }
    " /tmp/results)
    total="$1"
    success="$2"
    failed="$3"
    p95="$(awk "\$1 == 200 {print \$2}" /tmp/results | sort -n | \
      awk -v n="$success" "NR == int((n * 95 + 99) / 100) {print; found=1} END {if (!found) print 0}")"
    p99="$(awk "\$1 == 200 {print \$2}" /tmp/results | sort -n | \
      awk -v n="$success" "NR == int((n * 99 + 99) / 100) {print; found=1} END {if (!found) print 0}")"
    printf "Sustained readiness load: total=%s success=%s failed=%s p95=%ss p99=%ss\n" \
      "$total" "$success" "$failed" "$p95" "$p99"
    test "$total" -gt 0
    test "$failed" -eq 0
    awk -v actual="$p95" -v maximum="$LOAD_P95_MAX_SECONDS" "BEGIN { exit !(actual <= maximum) }"
  '

# Allow the pod up to two minutes to start, its configured load duration, and
# five seconds for the final in-flight curl requests to finish.
load_wait_seconds=$((load_seconds + 125))
for _ in $(seq 1 "$load_wait_seconds"); do
  phase="$(kubectl $context -n "$namespace" get pod qualification-load \
    -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  case "$phase" in
    Succeeded) break ;;
    Failed)
      load_output="$(kubectl $context -n "$namespace" logs qualification-load 2>/dev/null || true)"
      printf '%s\n' "$load_output"
      echo "sustained readiness load failed" >&2
      exit 1
      ;;
  esac
  sleep 1
done

load_phase="$(kubectl $context -n "$namespace" get pod qualification-load \
  -o jsonpath='{.status.phase}' 2>/dev/null || true)"
if [[ "$load_phase" != "Succeeded" ]]; then
  load_output="$(kubectl $context -n "$namespace" logs qualification-load 2>/dev/null || true)"
  printf '%s\n' "$load_output"
  echo "sustained readiness load did not complete successfully" >&2
  exit 1
fi
load_output="$(kubectl $context -n "$namespace" logs qualification-load)"
printf '%s\n' "$load_output"
qualification_stage="dependency recovery checks"

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
  echo "gateway remained ready after dependency loss" >&2
  return 1
}

qualification_stage="Redis recovery"
redis_recovery="running"
kubectl $context -n "$namespace" scale deployment/redis --replicas=0
wait_for_gateway_unready
kubectl $context -n "$namespace" scale deployment/redis --replicas=1
kubectl $context -n "$namespace" rollout status deployment/redis --timeout=5m
wait_for_gateway_ready
redis_recovery="passed"

# Cassandra is a required production dependency. Pause the signalable
# Cassandra child process so existing driver connections fail, preserving data.
qualification_stage="Cassandra recovery"
cassandra_recovery="running"
for pod in cassandra-0 cassandra-1 cassandra-2; do
  kubectl $context -n "$namespace" exec "$pod" -- \
    sh -ec 'kill -STOP "$(cat /tmp/cassandra.pid)"'
done
wait_for_gateway_unready
for pod in cassandra-0 cassandra-1 cassandra-2; do
  kubectl $context -n "$namespace" exec "$pod" -- \
    sh -ec 'kill -CONT "$(cat /tmp/cassandra.pid)"'
done
wait_for_gateway_ready
cassandra_recovery="passed"
kubectl $context -n "$namespace" exec qualification-traffic -- touch /tmp/strict

qualification_stage="gateway pod-loss recovery"
pod_recovery="running"
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
pod_recovery="passed"
qualification_stage="completed"

echo "Kubernetes qualification passed: three-worker kind cluster, production readiness, Redis/Cassandra recovery, and pod-loss traffic recovery"

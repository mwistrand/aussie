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
resource_output="not collected"
rolling_traffic_output="not run"
redis_recovery="not run"
redis_recovery_seconds="not measured"
cassandra_recovery="not run"
cassandra_recovery_seconds="not measured"
pod_recovery="not run"
node_drain="not run"
drained_node="not selected"
drain_pid=""
drain_log=""
rolling_deployment="not run"
rollback_deployment="not run"
route_convergence="not run"
websocket_probe="not run"
websocket_drain="not run"
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
    echo "- Load endpoints: /q/health/ready, /q/health/live, /q/metrics, /qualification-upstream/health"
    echo "- Redis recovery: $redis_recovery"
    echo "- Redis recovery time: $redis_recovery_seconds"
    echo "- Cassandra recovery: $cassandra_recovery"
    echo "- Cassandra recovery time: $cassandra_recovery_seconds"
    echo "- Gateway pod-loss recovery: $pod_recovery"
    echo "- Node drain: $node_drain"
    echo "- Drained node: $drained_node"
    echo "- Gateway rolling deployment: $rolling_deployment"
    echo "- Gateway rollback: $rollback_deployment"
    echo "- Routed HTTP convergence: $route_convergence"
    echo "- WebSocket handshake: $websocket_probe"
    echo "- WebSocket drain: $websocket_drain"
    echo
    echo "## Sustained operational and routed HTTP load"
    echo
    echo '```text'
    echo "$load_output"
    echo '```'
    echo
    echo "## Runtime resource snapshot"
    echo
    echo '```text'
    echo "$resource_output"
    echo '```'
    echo
    echo "## Rolling deployment, rollback, and node-drain readiness traffic"
    echo
    echo '```text'
    echo "$rolling_traffic_output"
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
  [[ "$node_drain" == "running" ]] && node_drain="failed"
  [[ "$rolling_deployment" == "running" ]] && rolling_deployment="failed"
  [[ "$rollback_deployment" == "running" ]] && rollback_deployment="failed"
  if [[ -n "$drain_pid" ]]; then
    kill "$drain_pid" >/dev/null 2>&1 || true
    wait "$drain_pid" >/dev/null 2>&1 || true
  fi
  if write_report "$status"; then
    :
  else
    report_status=$?
  fi
  if [[ "$status" -ne 0 && -n "$cluster" ]] && command -v kubectl >/dev/null 2>&1; then
    kubectl --context "kind-$cluster" -n "$namespace" get pods -o wide 2>/dev/null || true
    kubectl --context "kind-$cluster" -n "$namespace" get events --sort-by=.lastTimestamp 2>/dev/null || true
  fi
  if [[ -n "$cluster" && "$drained_node" != "not selected" ]] && command -v kubectl >/dev/null 2>&1; then
    if ! kubectl --context "kind-$cluster" uncordon "$drained_node" >/dev/null 2>&1; then
      echo "WARNING: unable to uncordon retained node $drained_node" >&2
      echo "Retry with: kubectl --context kind-$cluster uncordon $drained_node" >&2
    fi
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

# kind nodes use their own containerd and cannot see the runner's Docker login.
# Pull with the runner credentials, then load the exact digest into every node.
qualification_stage="pulling signed image"
docker pull "$qualification_image" >/dev/null

cluster="aussie-qualification-$$"
qualification_stage="creating kind cluster"
kind create cluster --name "$cluster" --config="$repo_root/tools/kind-qualification.yaml"
kind load docker-image "$qualification_image" --name "$cluster"

kubectl --context "kind-$cluster" create namespace "$namespace"
kubectl --context "kind-$cluster" label namespace "$namespace" \
  aussie.network/ingress=true \
  aussie.network/cassandra=true \
  aussie.network/redis=true

kubectl --context "kind-$cluster" -n "$namespace" create configmap qualification-upstream \
  --from-file=server.js="$repo_root/tools/qualification_upstream.js" \
  --from-file=websocket-probe.js="$repo_root/tools/qualification_websocket_probe.js"
kubectl --context "kind-$cluster" -n "$namespace" apply -f - <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: qualification-upstream
spec:
  replicas: 1
  selector:
    matchLabels:
      app: qualification-upstream
  template:
    metadata:
      labels:
        app: qualification-upstream
    spec:
      containers:
        - name: upstream
          image: node:24-alpine
          command: ["node", "/app/server.js"]
          ports:
            - name: http
              containerPort: 3000
          volumeMounts:
            - name: source
              mountPath: /app
              readOnly: true
      volumes:
        - name: source
          configMap:
            name: qualification-upstream
---
apiVersion: v1
kind: Service
metadata:
  name: qualification-upstream
spec:
  selector:
    app: qualification-upstream
  ports:
    - name: http
      port: 3000
      targetPort: http
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: qualification-upstream-egress
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: aussie
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: qualification-upstream
      ports:
        - protocol: TCP
          port: 3000
YAML
kubectl --context "kind-$cluster" -n "$namespace" rollout status deployment/qualification-upstream --timeout=5m

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
  --from-literal=AUTH_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  --from-literal=AUSSIE_BOOTSTRAP_KEY="aussie_v1_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"

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

render_qualification_manifests() {
  kubectl kustomize "$repo_root/deploy/kubernetes" \
    | sed \
        -e "s|ghcr.io/mwistrand/aussie@sha256:REPLACE_WITH_SIGNED_RELEASE_DIGEST|$AUSSIE_QUALIFICATION_IMAGE|g" \
        -e 's|cassandra.example.internal:9042|cassandra:9042|g' \
        -e 's|redis.example.internal:6379|redis:6379|g'
}

# Bootstrap the operator-created keyspace through the reviewed migration path,
# then restore the exact production manifest before qualification begins.
qualification_stage="running schema migration bootstrap"
render_qualification_manifests \
  | sed -e 's|CASSANDRA_RUN_MIGRATIONS: "false"|CASSANDRA_RUN_MIGRATIONS: "true"|g' \
  | kubectl $context -n "$namespace" apply -f -
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=15m

qualification_stage="applying steady-state production configuration"
render_qualification_manifests | kubectl $context -n "$namespace" apply -f -
kubectl $context -n "$namespace" patch configmap aussie --type=merge --patch-file=/dev/stdin <<'JSON'
{"data":{"AUSSIE_GATEWAY_SECURITY_ALLOW_PRIVATE_UPSTREAMS":"true","AUSSIE_GATEWAY_SECURITY_ALLOWED_UPSTREAM_HOSTS":"qualification-upstream"}}
JSON
kubectl $context -n "$namespace" patch deployment aussie --type=strategic --patch-file=/dev/stdin <<'YAML'
spec:
  template:
    spec:
      containers:
        - name: aussie
          env:
            - name: AUSSIE_BOOTSTRAP_ENABLED
              value: "true"
            - name: AUSSIE_BOOTSTRAP_KEY
              valueFrom:
                secretKeyRef:
                  name: aussie-production-secrets
                  key: AUSSIE_BOOTSTRAP_KEY
YAML
kubectl $context -n "$namespace" rollout restart deployment/aussie

qualification_stage="waiting for gateway readiness"
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=15m

qualification_stage="registering routed qualification service"
kubectl $context -n "$namespace" apply -f - <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: qualification-admin
spec:
  restartPolicy: Never
  containers:
    - name: curl
      image: curlimages/curl:8.10.1
      env:
        - name: BOOTSTRAP_KEY
          valueFrom:
            secretKeyRef:
              name: aussie-production-secrets
              key: AUSSIE_BOOTSTRAP_KEY
      command: ["sh", "-ec"]
      args:
        - >-
          curl --fail --silent --show-error --max-time 10
          -H "Authorization: Bearer $BOOTSTRAP_KEY"
          -H "Content-Type: application/json"
          --data '{"version":1,"serviceId":"qualification-upstream","baseUrl":"http://qualification-upstream:3000","routePrefix":"/qualification-upstream","defaultVisibility":"PUBLIC","defaultAuthRequired":false,"endpoints":[{"path":"/health","methods":["GET"],"visibility":"PUBLIC","authRequired":false},{"path":"/ws/echo","methods":[],"visibility":"PUBLIC","authRequired":false,"type":"WEBSOCKET"}]}'
          http://aussie:8080/admin/services
YAML
kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
  pod/qualification-admin --timeout=2m
kubectl $context -n "$namespace" delete pod qualification-admin --wait=true

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

qualification_stage="waiting for route convergence"
route_convergence="running"
for _ in $(seq 1 120); do
  gateway_ips="$(kubectl $context -n "$namespace" get pods \
    -l app.kubernetes.io/name=aussie \
    -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.status.podIP}{"\n"}{end}' \
    2>/dev/null || true)"
  gateway_ip_args=()
  while IFS= read -r gateway_ip; do
    [[ -n "$gateway_ip" ]] && gateway_ip_args+=("$gateway_ip")
  done <<< "$gateway_ips"
  gateway_count="${#gateway_ip_args[@]}"
  if [[ "$gateway_count" == "3" ]] && kubectl $context -n "$namespace" exec qualification-traffic -- \
    sh -ec '
      for gateway_ip do
        curl --fail --silent --show-error --max-time 5 \
          "http://${gateway_ip}:8080/qualification-upstream/health" >/dev/null
      done
    ' -- "${gateway_ip_args[@]}"; then
    route_convergence="passed"
    break
  fi
  sleep 1
done
if [[ "$route_convergence" != "passed" ]]; then
  route_convergence="failed"
  echo "timed out waiting for the routed service on all gateway replicas" >&2
  exit 1
fi

qualification_stage="checking WebSocket route"
websocket_probe="running"
kubectl $context -n "$namespace" apply -f - <<'YAML'
apiVersion: v1
kind: Pod
metadata:
  name: qualification-websocket
spec:
  restartPolicy: Never
  containers:
    - name: probe
      image: node:24-alpine
      env:
        - name: GATEWAY_WEBSOCKET_URL
          value: ws://aussie:8080/qualification-upstream/ws/echo
      command: ["node", "/app/websocket-probe.js"]
      volumeMounts:
        - name: source
          mountPath: /app
          readOnly: true
  volumes:
    - name: source
      configMap:
        name: qualification-upstream
YAML
kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
  pod/qualification-websocket --timeout=2m
websocket_probe="passed"
kubectl $context -n "$namespace" delete pod qualification-websocket --wait=true

qualification_stage="checking WebSocket drain"
websocket_drain="running"
websocket_drain_pod="$(kubectl $context -n "$namespace" get pods \
  -l app.kubernetes.io/name=aussie -o jsonpath='{.items[0].metadata.name}')"
websocket_drain_ip="$(kubectl $context -n "$namespace" get pod "$websocket_drain_pod" \
  -o jsonpath='{.status.podIP}')"
test -n "$websocket_drain_pod"
test -n "$websocket_drain_ip"
kubectl $context -n "$namespace" apply -f - <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: qualification-websocket-drain
spec:
  restartPolicy: Never
  containers:
    - name: probe
      image: node:24-alpine
      env:
        - name: GATEWAY_WEBSOCKET_URL
          value: ws://$websocket_drain_ip:8080/qualification-upstream/ws/echo
        - name: EXPECTED_CLOSE_CODE
          value: "1001"
        - name: CLOSE_AFTER_OPEN
          value: "false"
      command: ["node", "/app/websocket-probe.js"]
      volumeMounts:
        - name: source
          mountPath: /app
          readOnly: true
  volumes:
    - name: source
      configMap:
        name: qualification-upstream
YAML
kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Running \
  pod/qualification-websocket-drain --timeout=2m
for _ in $(seq 1 120); do
  if kubectl $context -n "$namespace" logs qualification-websocket-drain 2>/dev/null \
    | grep -q 'WebSocket handshake passed'; then
    break
  fi
  sleep 1
done
if ! kubectl $context -n "$namespace" logs qualification-websocket-drain 2>/dev/null \
  | grep -q 'WebSocket handshake passed'; then
  echo "WebSocket drain probe did not establish a connection" >&2
  exit 1
fi
# Start the bound when termination begins, not when the socket was opened.
websocket_drain_deadline="$(( $(date +%s) + 40 ))"
kubectl $context -n "$namespace" delete pod "$websocket_drain_pod" --wait=false
while [[ "$(date +%s)" -le "$websocket_drain_deadline" ]]; do
  websocket_drain_phase="$(kubectl $context -n "$namespace" get pod qualification-websocket-drain \
    -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  case "$websocket_drain_phase" in
    Succeeded)
      websocket_drain="passed"
      break
      ;;
    Failed)
      kubectl $context -n "$namespace" logs qualification-websocket-drain 2>/dev/null || true
      echo "WebSocket drain probe failed" >&2
      exit 1
      ;;
  esac
  sleep 1
done
if [[ "$websocket_drain" != "passed" ]]; then
  kubectl $context -n "$namespace" logs qualification-websocket-drain 2>/dev/null || true
  echo "WebSocket drain exceeded the 40-second termination bound" >&2
  exit 1
fi
kubectl $context -n "$namespace" delete pod qualification-websocket-drain --wait=true

qualification_stage="sustained readiness load"
kubectl $context -n "$namespace" run qualification-load \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false \
  --env="LOAD_SECONDS=$load_seconds" \
  --env="LOAD_INTERVAL_SECONDS=$load_interval" \
  --env="LOAD_P95_MAX_SECONDS=$load_p95_max" \
  --command -- sh -ec '
    : > /tmp/results
    pids=""
    request_number=0
    deadline=$(( $(date +%s) + LOAD_SECONDS ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      case $((request_number % 4)) in
        0) path="/q/health/ready" ;;
        1) path="/q/health/live" ;;
        2) path="/q/metrics" ;;
        3) path="/qualification-upstream/health" ;;
      esac
      (
        if result="$(curl --max-time 5 --silent --show-error --output /dev/null \
          --write-out "%{http_code} %{time_total}" "http://aussie:8080$path" 2>/dev/null)"; then
          :
        else
          result="000 0"
        fi
        printf "%s %s\n" "$path" "$result" >> /tmp/results
      ) &
      pids="$pids $!"
      request_number=$((request_number + 1))
      sleep "$LOAD_INTERVAL_SECONDS"
    done
    for pid in $pids; do
      wait "$pid" || true
    done

    for path in /q/health/ready /q/health/live /q/metrics /qualification-upstream/health; do
      set -- $(awk -v path="$path" "
        \$1 == path { total++; if (\$2 == 200) success++ }
        END { print total + 0, success + 0, (total - success) + 0 }
      " /tmp/results)
      total="$1"
      success="$2"
      failed="$3"
      p50="$(awk -v path="$path" "\$1 == path && \$2 == 200 {print \$3}" /tmp/results | sort -n | \
        awk -v n="$success" "NR == int((n * 50 + 99) / 100) {print; found=1} END {if (!found) print 0}")"
      p95="$(awk -v path="$path" "\$1 == path && \$2 == 200 {print \$3}" /tmp/results | sort -n | \
        awk -v n="$success" "NR == int((n * 95 + 99) / 100) {print; found=1} END {if (!found) print 0}")"
      p99="$(awk -v path="$path" "\$1 == path && \$2 == 200 {print \$3}" /tmp/results | sort -n | \
        awk -v n="$success" "NR == int((n * 99 + 99) / 100) {print; found=1} END {if (!found) print 0}")"
      printf "Endpoint %s: total=%s success=%s failed=%s p50=%ss p95=%ss p99=%ss\n" \
        "$path" "$total" "$success" "$failed" "$p50" "$p95" "$p99"
      test "$total" -gt 0
      test "$failed" -eq 0
      awk -v actual="$p95" -v maximum="$LOAD_P95_MAX_SECONDS" "BEGIN { exit !(actual <= maximum) }"
    done

    printf "Runtime resource snapshot:\n"
    metrics="$(curl --fail --silent --max-time 5 http://aussie:8080/q/metrics)"
    resource_metrics="$(printf "%s\n" "$metrics" | awk "
      /^#/ { next }
      {
        metric = \$0
        sub(/\\{.*/, \"\", metric)
        sub(/[[:space:]].*/, \"\", metric)
        if (metric ~ /^(jvm_memory_used_bytes|jvm_memory_max_bytes|jvm_buffer_memory_used_bytes|jvm_gc_pause_seconds|jvm_threads_live_threads|process_cpu_usage|system_cpu_usage|netty_eventexecutor_tasks_pending|netty_eventexecutor_workers|aussie_bulkhead_)/) {
          sample = \$0
          sub(/[[:space:]]+[^[:space:]]+$/, \"\", sample)
          print sample \" value=\" \$NF
        }
        if (metric ~ /^netty_eventexecutor_(tasks_pending|workers)$/) {
          event_loop_metrics++
        }
      }
      END { if (event_loop_metrics == 0) exit 1 }
    ")" || {
      echo "required netty event-loop metrics are missing from /q/metrics" >&2
      exit 1
    }
    printf "%s\n" "$resource_metrics"
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
resource_output="$(printf '%s\n' "$load_output" | sed -n '/^Runtime resource snapshot:/,$p')"
printf '%s\n' "$load_output"

qualification_stage="rolling deployment"
rolling_deployment="running"

wait_for_rolling_traffic_monitor() {
  for _ in $(seq 1 120); do
    phase="$(kubectl $context -n "$namespace" get pod qualification-rolling-traffic \
      -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    case "$phase" in
      Running) return 0 ;;
      Failed|Succeeded)
        kubectl $context -n "$namespace" logs qualification-rolling-traffic 2>/dev/null || true
        echo "rolling deployment traffic monitor ended before the restart" >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "timed out waiting for the rolling deployment traffic monitor" >&2
  return 1
}

kubectl $context -n "$namespace" run qualification-rolling-traffic \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false \
  --command -- sh -ec '
    : > /tmp/results
    while [ ! -f /tmp/stop ]; do
      if curl --fail --silent --output /dev/null --max-time 5 \
        http://aussie:8080/q/health/ready; then
        printf "success\\n" >> /tmp/results
      else
        printf "failure\\n" >> /tmp/results
      fi
      sleep 1
    done
    set -- $(awk "{ total++; if (\$1 == \"failure\") failed++ } END { print total + 0, failed + 0 }" /tmp/results)
    total="$1"
    failed="$2"
    printf "Rolling deployment, rollback, and node-drain readiness traffic: total=%s failed=%s\\n" "$total" "$failed"
    test "$total" -gt 0
    test "$failed" -eq 0
  '
wait_for_rolling_traffic_monitor

kubectl $context -n "$namespace" rollout restart deployment/aussie
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=15m
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
wait_for_gateway_ready
rolling_deployment="passed"

qualification_stage="rollback deployment"
rollback_deployment="running"
kubectl $context -n "$namespace" rollout undo deployment/aussie
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=15m
wait_for_gateway_ready

rollback_deployment="passed"

qualification_stage="node drain"
node_drain="running"
gateway_node=""
gateway_pod=""
while IFS= read -r node; do
  gateway_count="$(kubectl $context -n "$namespace" get pods \
    -l app.kubernetes.io/name=aussie --field-selector "spec.nodeName=$node" \
    --no-headers 2>/dev/null | awk 'END { print NR + 0 }')"
  if [[ "$gateway_count" == "1" ]]; then
    gateway_node="$node"
    gateway_pod="$(kubectl $context -n "$namespace" get pods \
      -l app.kubernetes.io/name=aussie --field-selector "spec.nodeName=$node" \
      -o jsonpath='{.items[0].metadata.name}')"
    break
  fi
done < <(kubectl $context get nodes -o name | sed 's#^node/##')

if [[ -z "$gateway_node" ]]; then
  echo "unable to find a node with exactly one gateway pod for PDB validation" >&2
  exit 1
fi
drained_node="$gateway_node"
second_gateway_pod=""
while IFS= read -r pod; do
  if [[ "$pod" != "$gateway_pod" ]]; then
    second_gateway_pod="$pod"
    break
  fi
done < <(kubectl $context -n "$namespace" get pods -l app.kubernetes.io/name=aussie \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
if [[ -z "$second_gateway_pod" ]]; then
  echo "unable to find a second gateway pod for PDB validation" >&2
  exit 1
fi

# Only gateway pods are selected, so Redis, Cassandra, and traffic probes stay
# on the cordoned node while this stage isolates gateway eviction behavior.
drain_log="/tmp/aussie-kubernetes-drain-$$.log"
kubectl $context drain "$gateway_node" \
  --pod-selector='app.kubernetes.io/name=aussie' \
  --ignore-daemonsets --delete-emptydir-data --timeout=10m \
  >"$drain_log" 2>&1 &
drain_pid=$!

# Wait until the first eviction consumes the PDB budget, then prove a second
# eviction is rejected before the replacement can become ready.
first_eviction_seen="false"
for _ in $(seq 1 120); do
  disruptions_allowed="$(kubectl $context -n "$namespace" get pdb aussie \
    -o jsonpath='{.status.disruptionsAllowed}' 2>/dev/null || true)"
  if [[ "$disruptions_allowed" == "0" ]]; then
    first_eviction_seen="true"
    break
  fi
  if ! kill -0 "$drain_pid" 2>/dev/null; then
    break
  fi
  sleep 1
done
if [[ "$first_eviction_seen" != "true" ]]; then
  echo "the first gateway eviction did not consume the PDB budget" >&2
  cat "$drain_log" >&2 || true
  exit 1
fi

if second_eviction_output="$(kubectl $context -n "$namespace" create eviction "$second_gateway_pod" 2>&1)"; then
  echo "PDB accepted a second gateway eviction" >&2
  printf '%s\n' "$second_eviction_output" >&2
  exit 1
fi
if [[ "${second_eviction_output,,}" != *"disruption budget"* \
  && "${second_eviction_output,,}" != *"disruptions allowed"* ]]; then
  echo "second gateway eviction failed for an unexpected reason" >&2
  printf '%s\n' "$second_eviction_output" >&2
  exit 1
fi

if ! wait "$drain_pid"; then
  cat "$drain_log" >&2 || true
  exit 1
fi
drain_pid=""
kubectl $context -n "$namespace" rollout status deployment/aussie --timeout=10m
ready_replicas="$(kubectl $context -n "$namespace" get deployment aussie -o jsonpath='{.status.readyReplicas}')"
test "$ready_replicas" = 3
wait_for_gateway_ready
kubectl $context uncordon "$gateway_node"
node_drain="passed"

kubectl $context -n "$namespace" exec qualification-rolling-traffic -- touch /tmp/stop
for _ in $(seq 1 120); do
  rolling_traffic_phase="$(kubectl $context -n "$namespace" get pod qualification-rolling-traffic \
    -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  case "$rolling_traffic_phase" in
    Succeeded) break ;;
    Failed)
      rolling_traffic_output="$(kubectl $context -n "$namespace" logs qualification-rolling-traffic 2>/dev/null || true)"
      printf '%s\n' "$rolling_traffic_output"
      echo "rolling deployment, rollback, or node-drain readiness traffic failed" >&2
      exit 1
      ;;
  esac
  sleep 1
done
if [[ "$rolling_traffic_phase" != "Succeeded" ]]; then
  rolling_traffic_output="$(kubectl $context -n "$namespace" logs qualification-rolling-traffic 2>/dev/null || true)"
  printf '%s\n' "$rolling_traffic_output"
  echo "rolling deployment, rollback, or node-drain readiness traffic did not complete" >&2
  exit 1
fi
rolling_traffic_output="$(kubectl $context -n "$namespace" logs qualification-rolling-traffic)"
printf '%s\n' "$rolling_traffic_output"

qualification_stage="dependency recovery checks"

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
recovery_started_at="$(date +%s)"
kubectl $context -n "$namespace" scale deployment/redis --replicas=0
wait_for_gateway_unready
kubectl $context -n "$namespace" scale deployment/redis --replicas=1
kubectl $context -n "$namespace" rollout status deployment/redis --timeout=5m
wait_for_gateway_ready
redis_recovery_seconds="$(( $(date +%s) - recovery_started_at ))s"
redis_recovery="passed"

# Cassandra is a required production dependency. Pause the signalable
# Cassandra child process so existing driver connections fail, preserving data.
qualification_stage="Cassandra recovery"
cassandra_recovery="running"
recovery_started_at="$(date +%s)"
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
cassandra_recovery_seconds="$(( $(date +%s) - recovery_started_at ))s"
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

echo "Kubernetes qualification passed: four-worker kind cluster, production readiness, HTTP/WebSocket routing and drain, PDB/node-drain validation, Redis/Cassandra recovery, and pod-loss traffic recovery"

#!/usr/bin/env bash
set -euo pipefail

# Disposable qualification for the repository-owned Kubernetes deployment.
# Signed release images are required unless the explicit experimental local mode is enabled.

qualification_allow_unreleased_image="${AUSSIE_QUALIFICATION_ALLOW_UNRELEASED_IMAGE:-false}"
qualification_image="${AUSSIE_QUALIFICATION_IMAGE:-not provided}"
qualification_artifact="signed release image"
qualification_image_id="not recorded"
load_seconds="${AUSSIE_QUALIFICATION_LOAD_SECONDS:-30}"
load_interval="${AUSSIE_QUALIFICATION_LOAD_INTERVAL_SECONDS:-0.1}"
load_p95_max="${AUSSIE_QUALIFICATION_LOAD_P95_MAX_SECONDS:-1}"
recovery_max_seconds="${AUSSIE_QUALIFICATION_RECOVERY_MAX_SECONDS:-120}"
websocket_capacity_connections="${AUSSIE_QUALIFICATION_WEBSOCKET_CONNECTIONS:-32}"
websocket_capacity_hold_seconds="${AUSSIE_QUALIFICATION_WEBSOCKET_HOLD_SECONDS:-30}"
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
dns_stall="not run"
dns_stall_seconds="not measured"
dns_stall_output="not run"
dns_policy_enforcement="not run"
dns_policy_enforcement_output="not run"
pod_recovery="not run"
pod_recovery_seconds="not measured"
node_drain="not run"
drained_node="not selected"
drain_pid=""
drain_log=""
rolling_deployment="not run"
rollback_deployment="not run"
route_convergence="not run"
websocket_probe="not run"
websocket_capacity="not run"
websocket_capacity_output="not run"
websocket_drain="not run"
telemetry_stall="not run"
telemetry_stall_seconds="not measured"
telemetry_connections="not observed"
cluster=""
namespace="aussie-qualification"
telemetry_namespace="aussie-telemetry"
keep_cluster="${AUSSIE_QUALIFICATION_KEEP_CLUSTER:-false}"

write_report() {
  local exit_status="$1"
  local report_parent
  report_parent="$(dirname "$report_file")"
  if [[ "$exit_status" -eq 0 ]]; then
    qualification_status="passed"
  fi
  if [[ "$qualification_allow_unreleased_image" == "true" && "$exit_status" -eq 0 ]]; then
    qualification_status="passed (experimental unreleased image)"
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
    echo "- Artifact: $qualification_artifact"
    echo "- Qualification image: $qualification_image"
    echo "- Local image ID: $qualification_image_id"
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
    echo "- Recovery time limit: ${recovery_max_seconds}s"
    echo "- Load endpoints: /q/health/ready, /q/health/live, /q/metrics, /qualification-upstream/health"
    echo "- Redis recovery: $redis_recovery"
    echo "- Redis recovery time: $redis_recovery_seconds"
    echo "- Cassandra recovery: $cassandra_recovery"
    echo "- Cassandra recovery time: $cassandra_recovery_seconds"
    echo "- DNS resolver stall: $dns_stall"
    echo "- DNS resolver stall check time: $dns_stall_seconds"
    echo "- DNS NetworkPolicy enforcement: $dns_policy_enforcement"
    echo "- Gateway pod-loss recovery: $pod_recovery"
    echo "- Gateway pod-loss recovery time: $pod_recovery_seconds"
    echo "- Node drain: $node_drain"
    echo "- Drained node: $drained_node"
    echo "- Gateway rolling deployment: $rolling_deployment"
    echo "- Gateway rollback: $rollback_deployment"
    echo "- Routed HTTP convergence: $route_convergence"
    echo "- WebSocket handshake: $websocket_probe"
    echo "- WebSocket capacity: $websocket_capacity"
    echo "- WebSocket capacity connections: $websocket_capacity_connections"
    echo "- WebSocket capacity hold: ${websocket_capacity_hold_seconds}s"
    echo "- WebSocket drain: $websocket_drain"
    echo "- Telemetry exporter stall: $telemetry_stall"
    echo "- Telemetry exporter stall check time: $telemetry_stall_seconds"
    echo "- Telemetry exporter connections observed: $telemetry_connections"
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
    echo
    echo "## WebSocket capacity"
    echo
    echo '```text'
    echo "$websocket_capacity_output"
    echo '```'
    echo
    echo "## DNS resolver stall"
    echo
    echo '```text'
    echo "$dns_stall_output"
    echo '```'
    echo
    echo "## DNS NetworkPolicy enforcement"
    echo
    echo '```text'
    echo "$dns_policy_enforcement_output"
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
  [[ "$dns_stall" == "running" ]] && dns_stall="failed"
  [[ "$dns_policy_enforcement" == "running" ]] && dns_policy_enforcement="failed"
  [[ "$pod_recovery" == "running" ]] && pod_recovery="failed"
  [[ "$node_drain" == "running" ]] && node_drain="failed"
  [[ "$rolling_deployment" == "running" ]] && rolling_deployment="failed"
  [[ "$rollback_deployment" == "running" ]] && rollback_deployment="failed"
  [[ "$websocket_capacity" == "running" ]] && websocket_capacity="failed"
  [[ "$telemetry_stall" == "running" ]] && telemetry_stall="failed"
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

case "$qualification_allow_unreleased_image" in
  true|false) ;;
  *) echo "AUSSIE_QUALIFICATION_ALLOW_UNRELEASED_IMAGE must be true or false" >&2; exit 1 ;;
esac
if [[ "$qualification_allow_unreleased_image" == "true" ]]; then
  qualification_artifact="experimental unreleased local image"
  qualification_image="${AUSSIE_QUALIFICATION_IMAGE:-aussie-qualification:local}"
  if [[ "$qualification_image" == *@sha256:* ]]; then
    echo "AUSSIE_QUALIFICATION_IMAGE must be a local image tag in experimental mode" >&2
    exit 1
  fi
elif [[ -z "${AUSSIE_QUALIFICATION_IMAGE:-}" ]]; then
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
case "$recovery_max_seconds" in
  ''|*[!0-9]*) echo "AUSSIE_QUALIFICATION_RECOVERY_MAX_SECONDS must be a positive integer" >&2; exit 1 ;;
esac
test "$load_seconds" -gt 0
awk -v value="$load_interval" 'BEGIN { exit !(value > 0) }'
awk -v value="$load_p95_max" 'BEGIN { exit !(value > 0) }'
test "$recovery_max_seconds" -gt 0
case "$websocket_capacity_connections" in
  ''|*[!0-9]*) echo "AUSSIE_QUALIFICATION_WEBSOCKET_CONNECTIONS must be a positive integer" >&2; exit 1 ;;
esac
case "$websocket_capacity_hold_seconds" in
  ''|*[!0-9]*) echo "AUSSIE_QUALIFICATION_WEBSOCKET_HOLD_SECONDS must be a positive integer" >&2; exit 1 ;;
esac
test "$websocket_capacity_connections" -gt 1
test "$websocket_capacity_hold_seconds" -gt 0

if [[ "$qualification_allow_unreleased_image" == "false" &&
  ! "$qualification_image" =~ ^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "AUSSIE_QUALIFICATION_IMAGE must be a full image@sha256:<64 hex> reference" >&2
  exit 1
fi

for command in docker kind kubectl openssl; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "$command is required for Kubernetes qualification" >&2
    exit 1
  }
done
if [[ "$qualification_allow_unreleased_image" == "false" ]] && ! command -v cosign >/dev/null 2>&1; then
  echo "cosign is required for signed release qualification" >&2
  exit 1
fi

if [[ "$qualification_allow_unreleased_image" == "true" ]]; then
  qualification_stage="building experimental local image"
  docker build \
    --file "$repo_root/api/src/main/docker/Dockerfile.jvm" \
    --tag "$qualification_image" \
    "$repo_root/api"
  qualification_image_id="$(docker image inspect --format '{{.Id}}' "$qualification_image")"
else
  cosign verify \
    --certificate-identity-regexp '^https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/.+$' \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com \
    "$qualification_image" >/dev/null

  # kind nodes use their own containerd and cannot see the runner's Docker login.
  # Pull with the runner credentials, then load the exact digest into every node.
  qualification_stage="pulling signed image"
  docker pull "$qualification_image" >/dev/null
  qualification_image_id="$(docker image inspect --format '{{.Id}}' "$qualification_image")"
fi

cluster="aussie-qualification-$$"
qualification_stage="creating kind cluster"
kind create cluster --name "$cluster" --config="$repo_root/tools/kind-qualification.yaml"
kind load docker-image "$qualification_image" --name "$cluster"

qualification_stage="installing policy-enforcing CNI"
kubectl --context "kind-$cluster" apply -f \
  https://raw.githubusercontent.com/projectcalico/calico/v3.33.0/manifests/calico.yaml >/dev/null
kubectl --context "kind-$cluster" -n kube-system rollout status daemonset/calico-node --timeout=10m
kubectl --context "kind-$cluster" -n kube-system rollout status deployment/calico-kube-controllers --timeout=10m
kubectl --context "kind-$cluster" wait --for=condition=Ready nodes --all --timeout=10m

kubectl --context "kind-$cluster" create namespace "$namespace"
kubectl --context "kind-$cluster" label namespace "$namespace" \
  aussie.network/ingress=true \
  aussie.network/cassandra=true \
  aussie.network/redis=true
kubectl --context "kind-$cluster" create namespace "$telemetry_namespace"
kubectl --context "kind-$cluster" label namespace "$telemetry_namespace" aussie.network/telemetry=true

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
apiVersion: v1
kind: Service
metadata:
  name: qualification-dns-stall-upstream
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

kubectl --context "kind-$cluster" -n "$telemetry_namespace" create configmap qualification-telemetry \
  --from-file=server.js="$repo_root/tools/qualification_telemetry_blackhole.js"
kubectl --context "kind-$cluster" -n "$telemetry_namespace" apply -f - <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
spec:
  replicas: 1
  selector:
    matchLabels:
      app: otel-collector
  template:
    metadata:
      labels:
        app: otel-collector
    spec:
      containers:
        - name: blackhole
          image: node:24-alpine
          command: ["node", "/app/server.js"]
          ports:
            - name: otlp-grpc
              containerPort: 4317
            - name: status
              containerPort: 4318
          readinessProbe:
            tcpSocket:
              port: otlp-grpc
            periodSeconds: 2
          volumeMounts:
            - name: source
              mountPath: /app
              readOnly: true
      volumes:
        - name: source
          configMap:
            name: qualification-telemetry
---
apiVersion: v1
kind: Service
metadata:
  name: otel-collector
spec:
  selector:
    app: otel-collector
  ports:
    - name: otlp-grpc
      port: 4317
      targetPort: otlp-grpc
    - name: status
      port: 4318
      targetPort: status
YAML
kubectl --context "kind-$cluster" -n "$telemetry_namespace" rollout status deployment/otel-collector --timeout=5m

zone=0
while IFS= read -r node; do
  kubectl --context "kind-$cluster" label "$node" \
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
          topologyKey: kubernetes.io/hostname
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
              /usr/local/bin/docker-entrypoint.sh cassandra -f &
              child=$!
              echo "$child" > /tmp/cassandra.pid
              wait "$child"
          env:
            - name: CASSANDRA_CLUSTER_NAME
              value: aussie-qualification
            - name: CASSANDRA_SEEDS
              value: cassandra-0.cassandra
            - name: CASSANDRA_DC
              value: datacenter1
            - name: CASSANDRA_RACK
              value: rack1
            - name: CASSANDRA_LISTEN_ADDRESS
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            - name: CASSANDRA_BROADCAST_ADDRESS
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
            - name: CASSANDRA_RPC_ADDRESS
              value: 0.0.0.0
            - name: CASSANDRA_BROADCAST_RPC_ADDRESS
              valueFrom:
                fieldRef:
                  fieldPath: status.podIP
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
cassandra_nodes=0
for _ in $(seq 1 60); do
  cassandra_nodes="$(kubectl $context -n "$namespace" exec cassandra-0 -- nodetool status 2>/dev/null \
    | awk '/^UN / { nodes++ } END { print nodes + 0 }' || true)"
  if [[ "$cassandra_nodes" == "3" ]]; then
    break
  fi
  sleep 5
done
test "$cassandra_nodes" = 3

kubectl $context -n "$namespace" run cassandra-admin \
  --image=cassandra:4.1.4 --restart=Never --rm -i --command -- \
  cqlsh cassandra -e "CREATE KEYSPACE aussie WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 2};"

render_qualification_manifests() {
  kubectl kustomize "$repo_root/deploy/kubernetes" \
    | sed \
        -e "s|ghcr.io/mwistrand/aussie@sha256:REPLACE_WITH_SIGNED_RELEASE_DIGEST|$qualification_image|g" \
        -e 's|cassandra.example.internal:9042|cassandra:9042|g' \
        -e 's|redis.example.internal:6379|redis:6379|g' \
        -e 's|otel-collector.example.internal:4317|otel-collector.aussie-telemetry.svc.cluster.local:4317|g'
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
{"data":{"AUSSIE_GATEWAY_SECURITY_ALLOW_PRIVATE_UPSTREAMS":"true","AUSSIE_GATEWAY_SECURITY_ALLOWED_UPSTREAM_HOSTS":"qualification-upstream,qualification-dns-stall-upstream","AUSSIE_TELEMETRY_TRACING_SAMPLING_RATE":"1.0"}}
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
          http://aussie:8080/admin/services &&
          curl --fail --silent --show-error --max-time 10
          -H "Authorization: Bearer $BOOTSTRAP_KEY"
          -H "Content-Type: application/json"
          --data '{"version":1,"serviceId":"qualification-dns-stall-upstream","baseUrl":"http://qualification-dns-stall-upstream:3000","routePrefix":"/qualification-dns-stall","defaultVisibility":"PUBLIC","defaultAuthRequired":false,"endpoints":[{"path":"/health","methods":["GET"],"visibility":"PUBLIC","authRequired":false}]}'
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

qualification_stage="checking WebSocket capacity"
websocket_capacity="running"
kubectl $context -n "$namespace" apply -f - <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: qualification-websocket-capacity
spec:
  restartPolicy: Never
  containers:
    - name: probe
      image: node:24-alpine
      env:
        - name: GATEWAY_WEBSOCKET_URL
          value: ws://aussie:8080/qualification-upstream/ws/echo
        - name: CONNECTION_COUNT
          value: "$websocket_capacity_connections"
        - name: HOLD_SECONDS
          value: "$websocket_capacity_hold_seconds"
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
websocket_capacity_wait_seconds=$((websocket_capacity_hold_seconds + 60))
if ! kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
  pod/qualification-websocket-capacity --timeout="${websocket_capacity_wait_seconds}s"; then
  websocket_capacity_output="$(kubectl $context -n "$namespace" logs qualification-websocket-capacity 2>/dev/null || true)"
  printf '%s\n' "$websocket_capacity_output"
  echo "WebSocket capacity probe failed" >&2
  exit 1
fi
websocket_capacity_output="$(kubectl $context -n "$namespace" logs qualification-websocket-capacity)"
websocket_capacity="passed"
kubectl $context -n "$namespace" delete pod qualification-websocket-capacity --wait=true

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
recovery_elapsed_seconds="$(( $(date +%s) - recovery_started_at ))"
redis_recovery_seconds="${recovery_elapsed_seconds}s"
test "$recovery_elapsed_seconds" -le "$recovery_max_seconds"
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
recovery_elapsed_seconds="$(( $(date +%s) - recovery_started_at ))"
cassandra_recovery_seconds="${recovery_elapsed_seconds}s"
test "$recovery_elapsed_seconds" -le "$recovery_max_seconds"
cassandra_recovery="passed"

qualification_stage="DNS resolver stall"
dns_stall="running"
dns_stall_started_at="$(date +%s)"
dns_stall_gateway_pod="$(kubectl $context -n "$namespace" get pods \
  -l app.kubernetes.io/name=aussie -o jsonpath='{.items[0].metadata.name}')"
dns_stall_gateway_ip="$(kubectl $context -n "$namespace" get pod "$dns_stall_gateway_pod" \
  -o jsonpath='{.status.podIP}')"
test -n "$dns_stall_gateway_pod"
test -n "$dns_stall_gateway_ip"
kubectl $context -n "$namespace" patch networkpolicy aussie --type=json -p='[{"op":"remove","path":"/spec/egress/0"}]'
dns_policy_enforcement="running"
kubectl $context -n "$namespace" run qualification-dns-policy \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false \
  --labels=app.kubernetes.io/name=aussie --command -- sh -ec '
    if curl --noproxy "*" --max-time 3 --silent --show-error \
      http://qualification-dns-stall-upstream:3000/health >/dev/null; then
      echo "DNS egress unexpectedly remained available"
      exit 1
    fi
    echo "DNS egress blocked by the enforced NetworkPolicy"
  '
kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
  pod/qualification-dns-policy --timeout=30s
dns_policy_enforcement_output="$(kubectl $context -n "$namespace" logs qualification-dns-policy)"
kubectl $context -n "$namespace" delete pod qualification-dns-policy --wait=true
dns_policy_enforcement="passed"
kubectl $context -n "$namespace" run qualification-dns-stall \
  --image=curlimages/curl:8.10.1 --restart=Never --attach=false \
  --env="GATEWAY_IP=$dns_stall_gateway_ip" --command -- sh -ec '
    route_result=/tmp/route-result
    route_body=/tmp/route-body
    rm -f "$route_result"
    rm -f "$route_body"
    route_exit=0
    dns_timeout_before="$(curl --fail --silent --show-error --noproxy '*' \
      http://$GATEWAY_IP:8080/q/metrics \
      | awk "\$1 == \"aussie_dns_lookup_timeouts_total\" { print \$2; exit }")"
    test -n "$dns_timeout_before"
    curl --max-time 8 --silent --show-error --noproxy '*' \
      --write-out "%{http_code} %{time_total}" \
      --output "$route_body" \
      "http://$GATEWAY_IP:8080/qualification-dns-stall-upstream/health" > "$route_result" 2>/tmp/route-error &
    route_pid=$!
    sleep 1
    kill -0 "$route_pid"
    test ! -s "$route_result"
    curl --fail --silent --show-error --noproxy '*' --max-time 3 \
      "http://$GATEWAY_IP:8080/q/health/live" >/dev/null
    curl --fail --silent --show-error --noproxy '*' --max-time 3 \
      "http://$GATEWAY_IP:8080/q/metrics" >/dev/null
    kill -0 "$route_pid"
    test ! -s "$route_result"
    wait "$route_pid" || route_exit=$?
    set -- $(cat "$route_result" 2>/dev/null || true)
    route_code="${1:-000}"
    route_time="${2:-8}"
    dns_timeout_after="$(curl --fail --silent --show-error --noproxy '*' \
      "http://$GATEWAY_IP:8080/q/metrics" \
      | awk "\$1 == \"aussie_dns_lookup_timeouts_total\" { print \$2; exit }")"
    test "$route_exit" -eq 0
    test "$route_code" = 502
    grep -Eq "\"code\"[[:space:]]*:[[:space:]]*\"bad_gateway\"" "$route_body"
    awk -v before="$dns_timeout_before" -v after="$dns_timeout_after" \
      "BEGIN { exit !(after > before) }"
    awk -v actual="$route_time" "BEGIN { exit !(actual < 7) }"
    printf "Routed request during DNS stall: exit=%s status=%s duration=%ss DNS timeouts=%s->%s\n" \
      "$route_exit" "$route_code" "$route_time" "$dns_timeout_before" "$dns_timeout_after"
  '
kubectl $context -n "$namespace" wait --for=jsonpath='{.status.phase}'=Succeeded \
  pod/qualification-dns-stall --timeout=30s
dns_stall_output="$(kubectl $context -n "$namespace" logs qualification-dns-stall)"
kubectl $context -n "$namespace" delete pod qualification-dns-stall --wait=true
kubectl $context -n "$namespace" patch networkpolicy aussie --type=json -p='[{"op":"add","path":"/spec/egress/0","value":{"to":[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"kube-system"}},"podSelector":{"matchLabels":{"k8s-app":"kube-dns"}}}],"ports":[{"protocol":"UDP","port":53},{"protocol":"TCP","port":53}]}}]'
dns_stall_seconds="$(( $(date +%s) - dns_stall_started_at ))s"
dns_stall="passed"

qualification_stage="telemetry exporter stall"
telemetry_stall="running"
telemetry_started_at="$(date +%s)"
kubectl $context -n "$namespace" exec qualification-traffic -- sh -ec '
  for _ in $(seq 1 10); do
    curl --fail --silent --show-error --max-time 10 http://aussie:8080/qualification-upstream/health >/dev/null
  done
'
for _ in $(seq 1 60); do
  telemetry_connections="$(kubectl $context -n "$namespace" exec qualification-traffic -- \
    sh -ec 'curl --fail --silent --show-error --max-time 5 http://otel-collector.aussie-telemetry.svc.cluster.local:4318/connections' \
    2>/dev/null || true)"
  if [[ "$telemetry_connections" =~ ^[1-9][0-9]*$ ]]; then
    break
  fi
  kubectl $context -n "$namespace" exec qualification-traffic -- \
    sh -ec 'curl --fail --silent --show-error --max-time 10 http://aussie:8080/qualification-upstream/health >/dev/null'
  sleep 1
done
if ! [[ "$telemetry_connections" =~ ^[1-9][0-9]*$ ]]; then
  echo "gateway did not establish an OTLP connection to the qualification exporter" >&2
  exit 1
fi
kubectl $context -n "$telemetry_namespace" scale deployment/otel-collector --replicas=0
sleep 5
kubectl $context -n "$namespace" exec qualification-traffic -- \
  sh -ec 'curl --fail --silent --show-error --max-time 10 http://aussie:8080/q/health/ready >/dev/null && curl --fail --silent --show-error --max-time 10 http://aussie:8080/qualification-upstream/health >/dev/null'
kubectl $context -n "$telemetry_namespace" scale deployment/otel-collector --replicas=1
kubectl $context -n "$telemetry_namespace" rollout status deployment/otel-collector --timeout=5m
telemetry_stall_seconds="$(( $(date +%s) - telemetry_started_at ))s"
telemetry_stall="passed"
kubectl $context -n "$namespace" exec qualification-traffic -- touch /tmp/strict

qualification_stage="gateway pod-loss recovery"
pod_recovery="running"
victim="$(kubectl $context -n "$namespace" get pods -l app.kubernetes.io/name=aussie \
  -o jsonpath='{.items[0].metadata.name}')"
test -n "$victim"
pod_recovery_started_at="$(date +%s)"
kubectl $context -n "$namespace" delete pod "$victim" --wait=false

wait_for_gateway_replacement_ready() {
  while true; do
    recovery_elapsed_seconds="$(( $(date +%s) - pod_recovery_started_at ))"
    if [[ "$recovery_elapsed_seconds" -gt "$recovery_max_seconds" ]]; then
      echo "gateway replacement readiness exceeded ${recovery_max_seconds}s" >&2
      return 1
    fi
    ready_replicas="$(kubectl $context -n "$namespace" get deployment aussie \
      -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
    # Exclude the deleted pod so a stale readyReplicas value cannot pass the budget.
    ready_replacement_pods="$(kubectl $context -n "$namespace" get pods \
      -l app.kubernetes.io/name=aussie \
      -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' \
      2>/dev/null | awk -v victim="$victim" '$1 != victim && $2 == "True" { count++ } END { print count + 0 }' || true)"
    if [[ "$ready_replicas" == "3" && "$ready_replacement_pods" == "3" ]]; then
      pod_recovery_seconds="${recovery_elapsed_seconds}s"
      return 0
    fi
    sleep 1
  done
}

qualification_stage="waiting for gateway replacement readiness"
wait_for_gateway_replacement_ready
pod_recovery="passed"

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
qualification_stage="completed"

echo "Kubernetes qualification passed: four-worker kind cluster, production readiness, HTTP/WebSocket routing and drain, DNS resolver stall, telemetry exporter stall, PDB/node-drain validation, Redis/Cassandra recovery, and pod-loss traffic recovery"

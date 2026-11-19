import copy
import unittest

from kubernetes_contract import (
    parse_yaml_documents,
    rollback_container_default,
    validate_config_map,
    validate_deployment,
    validate_network_policy,
)


DEPLOYMENT = """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: aussie
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    spec:
      serviceAccountName: aussie
      terminationGracePeriodSeconds: 45
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
      containers:
        - name: aussie
          image: example/aussie@sha256:abc
          envFrom:
            - configMapRef:
                name: aussie
          env:
            - name: CASSANDRA_USERNAME
              valueFrom:
                secretKeyRef:
                  name: aussie-production-secrets
                  key: CASSANDRA_USERNAME
            - name: CASSANDRA_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: aussie-production-secrets
                  key: CASSANDRA_PASSWORD
            - name: REDIS_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: aussie-production-secrets
                  key: REDIS_PASSWORD
            - name: AUTH_ENCRYPTION_KEY
              valueFrom:
                secretKeyRef:
                  name: aussie-production-secrets
                  key: AUTH_ENCRYPTION_KEY
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            requests:
              cpu: 500m
              memory: 1Gi
            limits:
              cpu: 2
              memory: 2Gi
          startupProbe:
            httpGet:
              path: /q/health/started
              port: http
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: http
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: http
"""


class KubernetesContractTest(unittest.TestCase):

    def test_exact_replica_count_rejects_near_match(self):
        deployment = parse_yaml_documents(DEPLOYMENT)[0]
        deployment["spec"]["replicas"] = 30

        with self.assertRaises(AssertionError):
            validate_deployment(deployment)

    def test_overlay_security_change_is_rejected(self):
        deployment = parse_yaml_documents(DEPLOYMENT)[0]
        broken = copy.deepcopy(deployment)
        broken["spec"]["template"]["spec"]["securityContext"]["runAsNonRoot"] = False

        with self.assertRaises(AssertionError):
            validate_deployment(broken)

    def test_dependency_port_change_is_rejected(self):
        policy = {
            "spec": {
                "policyTypes": ["Ingress", "Egress"],
                "egress": [
                    {
                        "ports": [
                            {"port": 53, "protocol": "UDP"},
                            {"port": 53, "protocol": "TCP"},
                            {"port": 9042, "protocol": "TCP"},
                            {"port": 6380, "protocol": "TCP"},
                        ]
                    }
                ],
            }
        }

        with self.assertRaises(AssertionError):
            validate_network_policy(policy)

    def test_tracing_must_be_enabled_in_the_production_config(self):
        config_map = {"data": self.production_config()}
        config_map["data"]["AUSSIE_TELEMETRY_TRACING_ENABLED"] = "false"

        with self.assertRaises(AssertionError):
            validate_config_map(config_map)

    def test_production_keyspace_creation_is_rejected(self):
        config_map = {"data": self.production_config()}
        config_map["data"]["CASSANDRA_CREATE_KEYSPACE"] = "true"

        with self.assertRaises(AssertionError):
            validate_config_map(config_map)

    def production_config(self):
        return {
            "QUARKUS_PROFILE": "prod",
            "AUSSIE_PRODUCTION_CONFIGURATION_VALIDATION_ENABLED": "true",
            "AUSSIE_STORAGE_REPOSITORY_PROVIDER": "cassandra",
            "AUSSIE_AUTH_STORAGE_PROVIDER": "cassandra",
            "AUSSIE_AUTH_ROLES_STORAGE_PROVIDER": "cassandra",
            "AUSSIE_TRANSLATION_CONFIG_STORAGE_PROVIDER": "cassandra",
            "AUSSIE_SESSION_STORAGE_PROVIDER": "redis",
            "AUSSIE_SESSION_ENABLED": "true",
            "AUSSIE_AUTH_PKCE_STORAGE_PROVIDER": "redis",
            "AUSSIE_AUTH_PKCE_ENABLED": "true",
            "AUSSIE_STORAGE_CACHE_PROVIDER": "redis",
            "AUSSIE_STORAGE_CACHE_ENABLED": "true",
            "AUSSIE_RATE_LIMITING_ENABLED": "true",
            "AUSSIE_RATE_LIMITING_REDIS_ENABLED": "true",
            "AUSSIE_RATE_LIMITING_FALLBACK_BEHAVIOR": "DENY",
            "AUSSIE_STORAGE_CASSANDRA_CONSISTENCY_LEVEL": "LOCAL_QUORUM",
            "CASSANDRA_RUN_MIGRATIONS": "false",
            "CASSANDRA_CREATE_KEYSPACE": "false",
            "AUSSIE_TELEMETRY_METRICS_ENABLED": "true",
            "AUSSIE_TELEMETRY_SECURITY_ENABLED": "true",
            "AUSSIE_TELEMETRY_ENABLED": "true",
            "AUSSIE_TELEMETRY_TRACING_ENABLED": "true",
            "OTEL_EXPORTER_OTLP_ENDPOINT": "http://collector:4317",
        }

    def test_probe_path_change_is_rejected(self):
        deployment = parse_yaml_documents(DEPLOYMENT)[0]
        broken = copy.deepcopy(deployment)
        broken["spec"]["template"]["spec"]["containers"][0]["readinessProbe"]["httpGet"]["path"] = "/health"

        with self.assertRaises(AssertionError):
            validate_deployment(broken)

    def test_container_env_cannot_override_protected_production_settings(self):
        deployment = parse_yaml_documents(DEPLOYMENT)[0]
        deployment["spec"]["template"]["spec"]["containers"][0]["env"].append(
            {"name": "CASSANDRA_RUN_MIGRATIONS", "value": "true"}
        )

        with self.assertRaises(AssertionError):
            validate_deployment(deployment)

    def test_rollback_default_is_read_from_container_input(self):
        workflow = """      container:
        description: Container name in the Deployment
        required: true
        default: api
        type: string
      namespace:
        default: aussie
        type: string
"""

        self.assertEqual(rollback_container_default(workflow), "api")


if __name__ == "__main__":
    unittest.main()

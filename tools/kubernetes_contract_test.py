import copy
import unittest

from kubernetes_contract import (
    parse_yaml_documents,
    rollback_container_default,
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

    def test_probe_path_change_is_rejected(self):
        deployment = parse_yaml_documents(DEPLOYMENT)[0]
        broken = copy.deepcopy(deployment)
        broken["spec"]["template"]["spec"]["containers"][0]["readinessProbe"]["httpGet"]["path"] = "/health"

        with self.assertRaises(AssertionError):
            validate_deployment(broken)

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

import unittest
from pathlib import Path

from config_contract import Property, active_property_references, has_runtime_consumer


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "api/src/main/java/aussie/core/config/TestConfig.java"


class RuntimeConsumerTest(unittest.TestCase):

    def property(self, **kwargs):
        values = {
            "name": "aussie.auth.key-rotation.storage",
            "value_type": "String",
            "default": None,
            "source": str(SOURCE.relative_to(ROOT)),
            "config_type": "KeyRotationConfig",
            "declaring_type": "KeyRotationConfig",
            "accessor_chain": ("storage",),
        }
        values.update(kwargs)
        return Property(**values)

    def test_unrelated_accessor_does_not_count(self):
        sources = {
            SOURCE: "interface KeyRotationConfig { String storage(); }",
            ROOT / "Uses.java": "final PkceConfig config; void use() { config.storage(); }",
        }

        self.assertFalse(has_runtime_consumer(self.property(), sources, set(), {}))

    def test_declared_config_chain_and_alias_count(self):
        item = self.property(
            name="aussie.resiliency.http.tls.handshake-timeout",
            config_type="ResiliencyConfig",
            declaring_type="TlsConfig",
            parent_type="HttpConfig",
            parent_method="tls",
            accessor_chain=("http", "tls", "handshakeTimeout"),
        )
        sources = {
            SOURCE: "interface ResiliencyConfig { }",
            ROOT / "Uses.java": "void use(ResiliencyConfig.HttpConfig config) { var tls = config.tls(); tls.handshakeTimeout(); }",
        }

        self.assertTrue(has_runtime_consumer(item, sources, set(), {}))

    def test_commented_placeholders_do_not_count(self):
        self.assertEqual(
            {"aussie.active"},
            active_property_references(
                [
                    "# quarkus.foo=${aussie.commented}",
                    "! quarkus.bar=${aussie.also-commented}",
                    "quarkus.baz=${aussie.active:default}",
                ]
            ),
        )


if __name__ == "__main__":
    unittest.main()

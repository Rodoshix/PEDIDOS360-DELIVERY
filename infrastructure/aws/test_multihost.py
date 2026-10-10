"""Offline configuration regressions. No daemon start, broker, SQL or AWS calls."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("multihost", ROOT / "multihost.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
ENV = {
    "IMAGE_REGISTRY": "registry.example.test/team", "IMAGE_TAG": "a" * 40,
    "FRONTEND_ORIGIN": "https://app.example.test", "PUBLIC_API_BASE_URL": "https://api.example.test/api",
    "ENTRA_TENANT_ID": "11111111-1111-4111-8111-111111111111",
    "ENTRA_API_CLIENT_ID": "22222222-2222-4222-8222-222222222222",
    "ENTRA_FRONTEND_CLIENT_ID": "33333333-3333-4333-8333-333333333333",
    "PAGOS_WORKER_CLIENT_ID": "44444444-4444-4444-8444-444444444444",
    "RDS_HOST": "database.example.test", "AWS_TLS_DIR": "C:/fixture/tls",
    "AWS_SECRETS_DIR": "C:/fixture/secrets", "EP2_PRIVATE_DIR": "C:/fixture/private",
    "HOST_A_PRIVATE_IP": "172.31.80.10", "HOST_B_PRIVATE_IP": "172.31.80.11",
    "RABBITMQ_DATA_DIR": "C:/fixture/data", "EP2_EXECUTION_SCOPE": "AWS_APPROVED",
    "EP2_EXPECTED_FS_ID": "synthetic", "PEDIDOS360_COORDINATION_MODE": "HTTP",
    "PEDIDOS360_RELIABILITY_PLATFORM_READY": "false", "PEDIDOS360_RELAY_MODE": "DISABLED",
    "PEDIDOS360_DECLARE_TOPOLOGY": "false", "EP2_BACKEND_NETWORK": "pedidos360-aws_services",
}


class MultihostTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.models = module.artifacts()
        cls.base = module.source("compose.yml", "compose.ep2-prepared.yml")
        cls.resolved = {}
        for name in cls.models:
            result = subprocess.run(["docker", "compose", "-f", name, "config", "--format", "json"],
                                    cwd=ROOT, env={**os.environ, **ENV}, capture_output=True, text=True)
            if result.returncode:
                raise AssertionError(result.stderr)
            cls.resolved[name] = json.loads(result.stdout)

    def test_generated_files_match_sources(self):
        for name, model in self.models.items():
            self.assertEqual((ROOT / name).read_text(), json.dumps(model, indent=2, sort_keys=True) + "\n")

    def test_exact_partition_and_local_dependency_closure(self):
        for host in ("a", "b"):
            model = self.models[f"compose.host-{host}.yml"]
            self.assertEqual(set(model["services"]), set(module.GROUPS[host]))
            for service in model["services"].values():
                self.assertLessEqual(set(service["depends_on"]), set(model["services"]))
        self.assertEqual(self.models["compose.host-a.yml"]["name"], "pedidos360-aws")

    def test_remote_names_preserve_tls_hosts(self):
        a = self.resolved["compose.host-a.yml"]["services"]
        b = self.resolved["compose.host-b.yml"]["services"]
        self.assertIn("productos=" + ENV["HOST_B_PRIVATE_IP"], a["pedidos"]["extra_hosts"])
        self.assertIn("pedidos=" + ENV["HOST_A_PRIVATE_IP"], b["pagos"]["extra_hosts"])
        self.assertIn("p360-rabbitmq=" + ENV["HOST_A_PRIVATE_IP"], b["pagos"]["extra_hosts"])
        self.assertEqual(b["pagos"]["environment"]["PEDIDOS_SERVICE_URL"], "https://pedidos:8085")

    def test_only_private_bindings_and_no_management_distribution(self):
        for file, model in self.resolved.items():
            ip = ENV["HOST_B_PRIVATE_IP"] if "host-b" in file else ENV["HOST_A_PRIVATE_IP"]
            for service in model["services"].values():
                for port in service.get("ports", []):
                    self.assertEqual(port["host_ip"], ip)
                    self.assertNotIn(port["target"], [22, 4369, 5672, 15671, 15672, 25672])
        broker = self.resolved["compose.broker-host-a.yml"]["services"]["rabbitmq"]
        self.assertEqual([p["target"] for p in broker["ports"]], [5671])

    def test_application_security_environment_and_health_unchanged(self):
        for host in ("a", "b"):
            for name, service in self.models[f"compose.host-{host}.yml"]["services"].items():
                self.assertEqual(service.get("environment"), self.base["services"][name].get("environment"))
                self.assertEqual(service.get("healthcheck"), self.base["services"][name].get("healthcheck"))
                if name != "frontend":
                    env = service["environment"]
                    if isinstance(env, list):
                        env = dict(item.split("=", 1) for item in env)
                    self.assertEqual(env["PEDIDOS360_RELAY_MODE"], "DISABLED")
                    self.assertEqual(env["PEDIDOS360_IDENTITY_PROOF_ENABLED"], "false")
                    self.assertEqual(env["SPRING_RABBITMQ_SSL_VERIFYHOSTNAME"], "true")
                    self.assertEqual(env["SPRING_RABBITMQ_SSL_VALIDATESERVERCERTIFICATE"], "true")

    def test_least_secrets_and_readonly_missing_path_failure(self):
        for file in ("compose.host-a.yml", "compose.host-b.yml"):
            model = self.models[file]
            used = {s["source"] for service in model["services"].values() for s in service.get("secrets", [])}
            self.assertEqual(used, set(model["secrets"]))
            for service in model["services"].values():
                for volume in service.get("volumes", []):
                    self.assertEqual(volume["type"], "bind")
                    self.assertTrue(volume["read_only"])
                    self.assertFalse(volume["bind"]["create_host_path"])
        b = self.models["compose.host-b.yml"]
        self.assertNotIn("ep2_actor_private", b["secrets"])
        self.assertNotIn("ep2_identity_private", b["secrets"])
        self.assertFalse(any("rabbitmq-data" in str(s) for s in b["services"].values()))

    def test_public_loopback_or_equal_addresses_rejected(self):
        for bad in ["8.8.8.8", "127.0.0.1", "0.0.0.0", "::1", ENV["HOST_A_PRIVATE_IP"]]:
            with self.subTest(bad=bad), patch.dict(os.environ, {**ENV, "HOST_B_PRIVATE_IP": bad}):
                with self.assertRaises(ValueError):
                    module.validate_addresses()
        with patch.dict(os.environ, ENV):
            module.validate_addresses()

    def test_remote_broker_network_and_storage_preserved(self):
        model = self.models["compose.broker-host-a.yml"]
        self.assertEqual(model["networks"]["backend"]["name"], "pedidos360-aws_services")
        self.assertTrue(model["networks"]["backend"]["external"])
        broker = model["services"]["rabbitmq"]
        self.assertEqual(broker["volumes"][0]["target"], "/var/lib/rabbitmq")
        self.assertIn("RABBITMQ_DATA_DIR", broker["volumes"][0]["source"])
        self.assertEqual(broker["entrypoint"], ["/bin/sh", "/ep2/bootstrap.sh"])

    def test_caller_environment_cannot_activate_prepared_models(self):
        active = {"PEDIDOS360_RELAY_MODE": "ACTIVE", "PEDIDOS360_COORDINATION_MODE": "RABBITMQ",
                  "PEDIDOS_CARRITO_MODE": "RABBITMQ", "BFF_QUERY_PAGOS_MODE": "RABBITMQ"}
        for file in self.models:
            result = subprocess.run(["docker", "compose", "-f", file, "config", "--format", "json"],
                                    cwd=ROOT, env={**os.environ, **ENV, **active}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            for service in json.loads(result.stdout)["services"].values():
                env = service.get("environment", {})
                for key, value in env.items():
                    if key.endswith("_MODE") and (key.startswith("BFF_QUERY_") or key in
                        ["PEDIDOS360_COORDINATION_MODE", "PEDIDOS_CARRITO_MODE", "CARRITO_PEDIDOS_MODE"]):
                        self.assertEqual(value, "HTTP")
                if "PEDIDOS360_RELAY_MODE" in env:
                    self.assertEqual(env["PEDIDOS360_RELAY_MODE"], "DISABLED")


if __name__ == "__main__":
    unittest.main()

"""Real local Compose lifecycle, synthetic sleepers only; no apps, SQL or AWS.

Uses a cached image (--pull never), random project names, no published ports,
secrets or host mounts. Cleanup touches only the two test projects.
"""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid


class TransitionTests(unittest.TestCase):
    def test_same_project_recreation_orphans_quiescence_and_rollback(self):
        image = "python:3.12-alpine"
        subprocess.run(["docker", "image", "inspect", image], check=True, capture_output=True)
        project = "p360-transition-test-" + uuid.uuid4().hex[:12]
        project_b = project + "-b"
        env = {**os.environ, "COMPOSE_REMOVE_ORPHANS": "false", "COMPOSE_IGNORE_ORPHANS": "false"}
        with tempfile.TemporaryDirectory(prefix=project) as directory:
            root = Path(directory)
            sleeper = {"image": image, "command": ["python", "-c", "import time; time.sleep(3600)"],
                       "read_only": True, "cap_drop": ["ALL"], "network_mode": None}
            sleeper.pop("network_mode")
            old = {"name": project, "services": {},
                   "networks": {"services": {"internal": True}, "egress": {}}}
            for name in ("client", "catalog", "worker", "broker-sentinel"):
                old["services"][name] = {**copy.deepcopy(sleeper), "networks": {"services": {}, "egress": {}}}
            old["services"]["client"]["depends_on"] = ["worker", "catalog"]
            new = copy.deepcopy(old)
            new["services"] = {"client": new["services"]["client"]}
            new["services"]["client"].pop("depends_on")
            new["services"]["client"]["extra_hosts"] = {"catalog": "172.31.80.11"}
            new["networks"].update({"edge": {}})
            new["services"]["client"]["networks"]["edge"] = {}
            for name, network in new["networks"].items():
                network["name"] = project + "_" + name
            b = {"name": project_b, "services": {"worker": copy.deepcopy(sleeper)}}
            for file, model in (("old.json", old), ("new.json", new), ("b.json", b)):
                (root / file).write_text(json.dumps(model), encoding="utf-8")

            def compose(file, *args):
                result = subprocess.run(["docker", "compose", "-f", str(root / file), *args],
                                        env=env, capture_output=True, text=True, timeout=60)
                self.assertEqual(result.returncode, 0, result.stderr)
                return result.stdout.strip()

            def inspect(container):
                return json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]

            def containers(name):
                ids = subprocess.check_output(["docker", "ps", "-a", "-q", "--filter",
                    "label=com.docker.compose.project=" + name], text=True).split()
                return {inspect(i)["Config"]["Labels"]["com.docker.compose.service"]: inspect(i) for i in ids}

            try:
                compose("old.json", "up", "-d", "--pull", "never")
                before = containers(project)
                network_id = before["worker"]["NetworkSettings"]["Networks"][project + "_services"]["NetworkID"]
                compose("new.json", "up", "-d", "--no-deps", "--pull", "never", "client")
                after = containers(project)
                # Omitted services persist AND RUN unless explicitly quiesced.
                for name in ("catalog", "worker", "broker-sentinel"):
                    self.assertEqual(before[name]["Id"], after[name]["Id"])
                    self.assertTrue(after[name]["State"]["Running"])
                # Same project/service is replaced, not cloned; old ID is gone.
                self.assertNotEqual(before["client"]["Id"], after["client"]["Id"])
                self.assertEqual(len(after), 4)
                self.assertEqual(after["client"]["NetworkSettings"]["Networks"][project + "_services"]["NetworkID"], network_id)
                self.assertIn(project + "_edge", after["client"]["NetworkSettings"]["Networks"])
                hosts = after["client"]["HostConfig"]["ExtraHosts"]
                self.assertTrue(any(h.replace(":", "=", 1) == "catalog=172.31.80.11" for h in hosts))
                compose("old.json", "stop", "-t", "5", "worker", "catalog")
                compose("new.json", "up", "-d", "--no-deps", "--pull", "never", "client")
                stopped = containers(project)
                for name in ("worker", "catalog"):
                    self.assertEqual(before[name]["Id"], stopped[name]["Id"])
                    self.assertFalse(stopped[name]["State"]["Running"])
                compose("b.json", "up", "-d", "--pull", "never", "worker")
                self.assertTrue(containers(project_b)["worker"]["State"]["Running"])
                self.assertFalse(containers(project)["worker"]["State"]["Running"])
                # Rollback: stop B before restarting preserved old A worker.
                compose("b.json", "stop", "-t", "5", "worker")
                compose("old.json", "up", "-d", "--no-deps", "--pull", "never", "worker", "catalog")
                recovered = containers(project)
                self.assertEqual(recovered["worker"]["Id"], before["worker"]["Id"])
                self.assertTrue(recovered["worker"]["State"]["Running"])
                self.assertFalse(containers(project_b)["worker"]["State"]["Running"])
                self.assertEqual(recovered["broker-sentinel"]["Id"], before["broker-sentinel"]["Id"])
            finally:
                # Only random local fixtures: never use this cleanup on A/B.
                compose("b.json", "down", "--timeout", "5")
                compose("old.json", "down", "--timeout", "5")
                edge = project + "_edge"
                result = subprocess.run(["docker", "network", "inspect", edge], capture_output=True)
                if result.returncode == 0:
                    subprocess.run(["docker", "network", "rm", edge], check=True, capture_output=True)


if __name__ == "__main__":
    unittest.main()

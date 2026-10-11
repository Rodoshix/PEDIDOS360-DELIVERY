"""Derive inactive multihost Compose artifacts; never deploy or call AWS."""
import argparse
import copy
import ipaddress
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
GROUPS = {"a": ("frontend", "bff", "usuarios", "pedidos"),
          "b": ("restaurantes", "productos", "carrito", "pagos")}


def source(*files):
    command = ["docker", "compose"]
    for file in files:
        command += ["-f", file]
    command += ["config", "--no-interpolate", "--no-path-resolution", "--format", "json"]
    return json.loads(subprocess.check_output(command, cwd=ROOT, text=True))


def derive(base, host):
    model = copy.deepcopy(base)
    model["name"] = "pedidos360-aws" if host == "a" else "pedidos360-aws-b"
    names = GROUPS[host]
    model["services"] = {name: model["services"][name] for name in names}
    remote = {name: "${HOST_B_PRIVATE_IP:?private host B address required}"
              for name in GROUPS["b"]} if host == "a" else {
                  "usuarios": "${HOST_A_PRIVATE_IP:?private host A address required}",
                  "pedidos": "${HOST_A_PRIVATE_IP:?private host A address required}",
                  "p360-rabbitmq": "${HOST_A_PRIVATE_IP:?private host A address required}"}
    ports = {"frontend": (8080, 8080), "bff": (8443, 8080), "usuarios": (8081, 8081),
             "pedidos": (8085, 8085), "restaurantes": (8082, 8082),
             "productos": (8083, 8083), "carrito": (8084, 8084), "pagos": (8086, 8086)}
    secrets, networks = set(), set()
    for name, service in model["services"].items():
        service["depends_on"] = {k: v for k, v in service.get("depends_on", {}).items() if k in names}
        service["extra_hosts"] = copy.deepcopy(remote) if name != "frontend" else {}
        published, target = ports[name]
        service["ports"] = [{"host_ip": "${HOST_" + host.upper() + "_PRIVATE_IP:?private bind address required}",
                             "target": target, "published": str(published), "protocol": "tcp"}]
        if name == "frontend":
            service["networks"] = {"edge": {}}
        else:
            service["stop_grace_period"] = "90s"
        # --no-interpolate cannot classify short bind syntax reliably. Make all
        # TLS binds explicit and fail if the host file is absent.
        for volume in service.get("volumes", []):
            volume.pop("volume", None)
            volume["type"] = "bind"
            volume["bind"] = {"create_host_path": False}
            volume["read_only"] = True
        secrets.update(s["source"] for s in service.get("secrets", []))
        networks.update(service.get("networks", {}))
    model["secrets"] = {k: v for k, v in model.get("secrets", {}).items() if k in secrets}
    model["networks"] = {k: copy.deepcopy(base["networks"].get(k, {})) for k in networks}
    for name, network in model["networks"].items():
        network["name"] = model["name"] + "_" + name
    return model


def broker(base):
    model = copy.deepcopy(base)
    service = model["services"]["rabbitmq"]
    service["environment"].update({
        "PEDIDOS360_COORDINATION_MODE": "HTTP",
        "PEDIDOS360_RELIABILITY_PLATFORM_READY": "false",
        "PEDIDOS360_RELAY_MODE": "DISABLED",
        "PEDIDOS360_DECLARE_TOPOLOGY": "false",
    })
    service["ports"] = [{"host_ip": "${HOST_A_PRIVATE_IP:?private host A address required}",
                         "target": 5671, "published": "5671", "protocol": "tcp"}]
    service["networks"]["broker-access"] = {}
    model["networks"]["backend"]["name"] = "pedidos360-aws_services"
    model["networks"]["broker-access"] = {"name": "pedidos360-ep2-broker_access"}
    for volume in service["volumes"]:
        if volume["source"].startswith("./"):
            volume["source"] = "./rabbitmq/" + volume["source"][2:]
            volume["bind"] = {"create_host_path": False}
    return model


def artifacts():
    base = source("compose.yml", "compose.ep2-prepared.yml")
    return {"compose.host-a.yml": derive(base, "a"), "compose.host-b.yml": derive(base, "b"),
            "compose.broker-host-a.yml": broker(source("rabbitmq/compose.rabbitmq.yml"))}


def validate_addresses():
    addresses = []
    ranges = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    for key in ("HOST_A_PRIVATE_IP", "HOST_B_PRIVATE_IP"):
        address = ipaddress.ip_address(os.environ[key])
        if not any(address in network for network in ranges):
            raise ValueError("Host addresses must be RFC1918 IPv4")
        addresses.append(address)
    if addresses[0] == addresses[1]:
        raise ValueError("Host addresses must be distinct")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("generate", "check", "validate"))
    action = parser.parse_args().action
    models = artifacts()
    for file, model in models.items():
        content = json.dumps(model, indent=2, sort_keys=True) + "\n"
        path = ROOT / file
        if action == "generate":
            path.write_text(content, encoding="utf-8", newline="\n")
        elif path.read_text(encoding="utf-8") != content:
            raise ValueError("Generated artifact differs: " + file)
    if action == "validate":
        validate_addresses()
        for file in models:
            subprocess.run(["docker", "compose", "-f", file, "config", "--quiet"], cwd=ROOT, check=True)
        print("Private addresses and Compose configuration valid; no deployment performed")
        return
    print("Artifacts " + ("generated" if action == "generate" else "match canonical sources"))


if __name__ == "__main__":
    main()

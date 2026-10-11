"""Verify the portable evidence package; local files only, no network or writes."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent
manifest = json.loads((root / "HASHES.json").read_text(encoding="utf-8"))
for item in manifest:
    path = (root / item["path"]).resolve()
    if not path.is_relative_to(root):
        raise SystemExit("Invalid path in evidence manifest")
    if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
        raise SystemExit("Hash mismatch: " + item["path"])
print("PASS: " + str(len(manifest)) + " portable evidence hashes")

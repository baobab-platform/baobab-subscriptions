#!/usr/bin/env python3
"""Regenerate the complete, immutable Shared bundle and JAR resource inventory.

Read Git blobs rather than mutable working files; validate closure and entry
points before replacing only generated assets. --check compares full inventory,
bytes and provenance, including stale-file removal. No runtime schema retrieval.
Requires Python 3 and PyYAML (maintainer/CI tooling only, never a runtime dependency).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlsplit

import yaml

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/main/resources/contracts"
ENTRIES = {'internal_authority': ('subscriptions/v1/internal-authority.schema.json', ''),
 'tenant_id': ('subscriptions/v1/internal-authority.schema.json', '/properties/tenant_id'),
 'product_subscription_id': ('subscriptions/v1/internal-authority.schema.json',
                             '/properties/product_subscription_id'),
 'classification_reference': ('subscriptions/v1/internal-authority.schema.json',
                              '/properties/classification_reference'),
 'envelope': ('events/v1/envelope.schema.json', ''),
 'SponsorshipActivated': ('admission/v2/founding-lifecycle-events.schema.json',
                          '/$defs/SponsorshipActivated'),
 'SponsorshipSuspended': ('admission/v2/founding-lifecycle-events.schema.json',
                          '/$defs/SponsorshipSuspended'),
 'SponsorshipRevoked': ('admission/v2/founding-lifecycle-events.schema.json', '/$defs/SponsorshipRevoked'),
 'SponsorshipExpired': ('admission/v2/founding-lifecycle-events.schema.json', '/$defs/SponsorshipExpired'),
 'DeferralActivated': ('admission/v2/founding-lifecycle-events.schema.json', '/$defs/DeferralActivated'),
 'DeferralRevoked': ('admission/v2/founding-lifecycle-events.schema.json', '/$defs/DeferralRevoked'),
 'DeferralExpired': ('admission/v2/founding-lifecycle-events.schema.json', '/$defs/DeferralExpired')}


def generate(shared: Path) -> dict[str, bytes]:
    lock = yaml.safe_load((ROOT / "contracts.lock.yaml").read_bytes())
    pin = lock["source"]["commit"]

    def git(*args: str) -> bytes:
        return subprocess.check_output(["git", "-C", str(shared), *args])

    if git("rev-parse", "HEAD").decode().strip() != pin:
        raise ValueError("Shared checkout must be at the exact lock pin")
    declared = lock["contracts"]
    if len(declared) != len(set(declared)):
        raise ValueError("Duplicate lock paths")
    documents: dict[str, bytes] = {}
    for path in declared:
        if not path.startswith("contracts/") or posixpath.normpath(path) != path:
            raise ValueError(f"Unsafe lock path: {path}")
        documents[path] = git("show", f"{pin}:{path}")

    def walk(node: object, path: str) -> None:
        if isinstance(node, dict):
            for key in ("$ref", "$dynamicRef"):
                ref = node.get(key)
                if isinstance(ref, str):
                    file = ref.split("#", 1)[0]
                    if file:
                        if urlsplit(file).scheme or file.startswith("/"):
                            raise ValueError(f"Remote/absolute reference forbidden: {ref}")
                        target = posixpath.normpath(posixpath.join(posixpath.dirname(path), file))
                        if target not in documents:
                            raise ValueError(f"Declare consumed closure path: {target}")
            for child in node.values():
                walk(child, path)
        elif isinstance(node, list):
            for child in node:
                walk(child, path)

    for path, raw in documents.items():
        if path.endswith(".schema.json"):
            schema = json.loads(raw)
            expected_id = "https://contracts.baobab-platform.com/" + path.removeprefix("contracts/")
            if schema.get("$id") != expected_id:
                raise ValueError(f"Unexpected canonical schema id: {path}")
            walk(schema, path)
    entries = {}
    for name, (path, pointer) in ENTRIES.items():
        schema = json.loads(documents["contracts/" + path])
        node = schema
        for part in pointer.strip("/").split("/") if pointer else []:
            node = node[part.replace("~1", "/").replace("~0", "~")]
        entries[name] = schema["$id"] + ("#" + pointer if pointer else "")
    manifest = {
        "repository": lock["source"]["repository"], "commit": pin,
        "entries": entries,
        "files": {path.removeprefix("contracts/"): hashlib.sha256(raw).hexdigest()
                  for path, raw in sorted(documents.items())},
    }
    files = {path.removeprefix("contracts/"): raw for path, raw in documents.items()}
    files["provenance.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    return files



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shared-checkout", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files = generate(args.shared_checkout)
    if args.check:
        actual = {str(p.relative_to(TARGET)): p.read_bytes()
                  for p in TARGET.rglob("*") if p.is_file()}
        if actual != files:
            raise ValueError("Generated bundle differs (bytes, provenance or complete inventory)")
    else:
        # Hand-written source lives outside the generated directory.
        if TARGET.exists():
            shutil.rmtree(TARGET)
        for path, raw in files.items():
            out = TARGET / path
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(raw)
    print(f"Exact Shared bundle verified: {len(files) - 1} files, {len(ENTRIES)} entries")


if __name__ == "__main__":
    main()

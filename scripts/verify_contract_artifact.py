#!/usr/bin/env python3
"""Inspect the real package's full contract inventory/bytes, then run owner probes.

The release JAR and its existing lib/ manifest layout are copied unchanged to an
isolated directory. No source classes, test classpath or Shared resources can
silently satisfy missing runtime assets/dependencies.
"""
import argparse
import subprocess
import tempfile
import zipfile
import shutil
from pathlib import Path

from sync_contracts import ROOT, generate


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shared-checkout", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    args = parser.parse_args()
    expected = generate(args.shared_checkout)
    with zipfile.ZipFile(args.jar) as jar:
        actual = {name.removeprefix("contracts/"): jar.read(name)
                  for name in jar.namelist() if name.startswith("contracts/") and not name.endswith("/")}
        if actual != expected:
            raise ValueError("Packaged original contract bytes/inventory differ")
        # Builders may use newer JDKs; the supported application target remains 21.
        for name in jar.namelist():
            if name.endswith(".class") and int.from_bytes(jar.read(name)[6:8], "big") > 65:
                raise ValueError("Application class exceeds Java 21 target")
    with tempfile.TemporaryDirectory(prefix="subscriptions-contract-package-") as temporary:
        target = Path(temporary) / "baobab-subscriptions.jar"
        shutil.copyfile(args.jar, target)
        shutil.copytree(args.jar.parent / "lib", target.parent / "lib")
        subprocess.run(["java", "-jar", str(target), "contract-self-test"], cwd=temporary, check=True)
        subprocess.run(["java", "-jar", str(target), "contract-conformance",
                        str(ROOT / "src/test/resources/authority-contract-cases.json")], cwd=temporary, check=True)
    print("Isolated JAR original bytes/inventory and Java 21 target verified")


if __name__ == "__main__":
    main()

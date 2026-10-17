#!/usr/bin/env python3
"""Run production Java process/protocol/queue/lock regressions without Android or a phone."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_config import executable, java_home


class BackendRuntimeTests(unittest.TestCase):
    def test_production_runtime_guards(self):
        production = ROOT / "src/io/github/dcl"
        sources = [
            production / (name + ".java")
            for name in (
                "BoundedProcess",
                "BoundedLine",
                "RequestTask",
                "RestorePlan",
                "ProfileLock",
            )
        ]
        sources.append(ROOT / "tests/java/io/github/dcl/BackendRuntimeTest.java")
        jdk = java_home()
        with tempfile.TemporaryDirectory(prefix="dcl-backend-tests-") as directory:
            subprocess.run(
                [
                    str(executable(jdk / "bin", "javac")),
                    "--release",
                    "17",
                    "-encoding",
                    "UTF-8",
                    "-d",
                    directory,
                    *map(str, sources),
                ],
                check=True,
                timeout=60,
            )
            result = subprocess.run(
                [
                    str(executable(jdk / "bin", "java")),
                    "-cp",
                    directory,
                    "io.github.dcl.BackendRuntimeTest",
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=True,
                timeout=45,
            )
            self.assertIn("22 backend runtime tests passed", result.stdout)
            print(result.stdout, end="")


if __name__ == "__main__":
    unittest.main()

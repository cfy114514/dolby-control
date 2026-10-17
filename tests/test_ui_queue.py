#!/usr/bin/env python3
"""Exercise the production SoundAlive intent scheduler on the host JVM."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_config import executable, java_home


class UiQueueTests(unittest.TestCase):
    def test_production_scheduler(self):
        with tempfile.TemporaryDirectory(prefix="dcl-ui-queue-") as directory:
            jdk = java_home()
            sources = [
                ROOT / "src/io/github/dcl/app/LatestTask.java",
                ROOT / "tests/java/io/github/dcl/app/LatestTaskTest.java",
            ]
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
            subprocess.run(
                [
                    str(executable(jdk / "bin", "java")),
                    "-cp",
                    directory,
                    "io.github.dcl.app.LatestTaskTest",
                ],
                check=True,
                timeout=30,
            )


if __name__ == "__main__":
    unittest.main()

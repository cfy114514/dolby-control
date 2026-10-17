#!/usr/bin/env python3
"""Compile real application classes and inject failures into the production reload target."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build_config import CONFIG, executable, java_home, sdk_root


class ReloadRecoveryTests(unittest.TestCase):
    def test_original_route_and_profile_survive_failed_reload(self):
        android = sdk_root() / "platforms" / CONFIG["platform"] / "android.jar"
        api = ROOT / "vendor/xposed-api-101.jar"
        sources = sorted(
            path for path in (ROOT / "src").rglob("*.java") if path.name != "BuildIdentity.java"
        )
        sources.append(ROOT / "tests/java/io/github/dcl/RuntimeEditReloadTest.java")
        jdk = java_home()
        with tempfile.TemporaryDirectory(prefix="dcl-reload-tests-") as directory:
            identity = Path(directory) / "BuildIdentity.java"
            identity.write_text(
                "package io.github.dcl.app; public final class BuildIdentity {"
                'public static final String TOKEN="test", NATIVE_SHA256="test";}',
                encoding="utf-8",
            )
            subprocess.run(
                [
                    str(executable(jdk / "bin", "javac")),
                    "--release",
                    "17",
                    "-encoding",
                    "UTF-8",
                    "-cp",
                    os.pathsep.join(map(str, (android, api))),
                    "-d",
                    directory,
                    *map(str, sources),
                    str(identity),
                ],
                check=True,
                timeout=60,
            )
            result = subprocess.run(
                [
                    str(executable(jdk / "bin", "java")),
                    "-cp",
                    directory,
                    "io.github.dcl.RuntimeEditReloadTest",
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=True,
                timeout=15,
            )
            self.assertIn("11 reload recovery tests passed", result.stdout)
            print(result.stdout, end="")


if __name__ == "__main__":
    unittest.main()

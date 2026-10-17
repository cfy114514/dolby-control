#!/usr/bin/env python3
"""Compile a separate on-device protocol probe; optionally push its DEX over ADB."""

import argparse
import os

from build_config import CONFIG, ROOT, executable, java_home, run, sdk_root
from device_probe import adb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--deploy",
        action="store_true",
        help="Push the probe DEX; does not install or launch the application",
    )
    args = parser.parse_args()
    android = (
        sdk_root()
        / "platforms"
        / os.environ.get("DCL_ANDROID_PLATFORM", CONFIG["platform"])
        / "android.jar"
    )
    classes, output = ROOT / "build/probe-classes", ROOT / "build/probe-dex"
    identity = ROOT / "build/generated/io/github/dcl/app/BuildIdentity.java"
    if not identity.is_file():
        parser.error("Build the APK first to generate its identity")
    classes.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    jdk = java_home()
    run(
        [
            executable(jdk / "bin", "javac"),
            "--release",
            "17",
            "-encoding",
            "UTF-8",
            "-cp",
            android,
            "-d",
            classes,
            ROOT / "tools/DeviceProbe.java",
            ROOT / "src/io/github/dcl/app/RootClient.java",
            ROOT / "src/io/github/dcl/BoundedLine.java",
            ROOT / "src/io/github/dcl/BoundedProcess.java",
            identity,
        ]
    )
    d8 = sdk_root() / "build-tools" / CONFIG["build_tools"] / "lib/d8.jar"
    run(
        [
            executable(jdk / "bin", "java"),
            "-cp",
            d8,
            "com.android.tools.r8.D8",
            "--lib",
            android,
            "--min-api",
            CONFIG["min_sdk"],
            "--output",
            output,
            *sorted(classes.rglob("*.class")),
        ]
    )
    dex = output / "classes.dex"
    if args.deploy:
        adb("push", str(dex), "/data/local/tmp/dcl-probe.dex")
    print(dex)


if __name__ == "__main__":
    main()

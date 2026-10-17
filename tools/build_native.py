#!/usr/bin/env python3
"""Build the fixed-table Android helper or a host-only transaction fixture."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess

from build_config import ROOT, executable, ndk_root, ndk_toolchain


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=ndk_root())
    fixtures = parser.add_mutually_exclusive_group()
    fixtures.add_argument(
        "--fixture-library",
        type=Path,
        help="Build a Windows DLL or POSIX shared library for host tests",
    )
    fixtures.add_argument(
        "--fixture-dll", type=Path, help="Compatibility alias for --fixture-library"
    )
    fixtures.add_argument(
        "--cow-fixture", type=Path, help="Build a separate Android self-memory fixture"
    )
    args = parser.parse_args()
    fixture = args.fixture_library or args.fixture_dll
    if fixture and os.name != "nt":
        compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("g++")
        if not compiler:
            parser.error("Host tests require CXX, clang++ or g++")
        output = fixture.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        command = [
            compiler,
            "-shared",
            "-fPIC",
            "-O2",
            "-std=c++17",
            "-Wall",
            "-Wextra",
            "-Werror",
            str(ROOT / "src/native/table_fixture.cpp"),
            "-o",
            str(output),
        ]
    else:
        toolchain = ndk_toolchain(args.ndk)
        compiler = executable(toolchain / "bin", "clang++")
        if fixture:
            output = fixture.resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            object_file = output.with_suffix(".obj")
            subprocess.run(
                [
                    str(compiler),
                    "--target=x86_64-pc-windows-msvc",
                    "-c",
                    "-fno-exceptions",
                    "-fno-rtti",
                    "-fno-stack-protector",
                    "-ffreestanding",
                    "-O2",
                    "-std=c++17",
                    str(ROOT / "src/native/table_fixture.cpp"),
                    "-o",
                    str(object_file),
                ],
                check=True,
                timeout=120,
            )
            command = [
                str(executable(toolchain / "bin", "ld.lld")),
                "-flavor",
                "link",
                "/dll",
                "/noentry",
                "/nodefaultlib",
                "/out:" + str(output),
                str(object_file),
            ]
        else:
            output = (
                args.cow_fixture.resolve()
                if args.cow_fixture
                else ROOT / "assets/backend/table-runtime"
            )
            output.parent.mkdir(parents=True, exist_ok=True)
            command = [
                str(compiler),
                "--target=aarch64-linux-android26",
                "--sysroot=" + str(toolchain / "sysroot"),
                "-static-libstdc++",
                "-fPIE",
                "-pie",
                "-O2",
                "-std=c++17",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-fstack-protector-strong",
                "-D_FORTIFY_SOURCE=2",
                "-Wl,-z,relro,-z,now",
                "-Wl,-z,max-page-size=16384",
                str(
                    ROOT
                    / "src/native"
                    / ("table_cow_fixture.cpp" if args.cow_fixture else "table_runtime.cpp")
                ),
                "-o",
                str(output),
            ]
    subprocess.run(command, check=True, timeout=180)
    print(output)


if __name__ == "__main__":
    main()

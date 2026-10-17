"""Shared, portable tool discovery. Importing this module has no side effects."""

import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / "project.json").read_text(encoding="utf-8"))
DEPENDENCIES = json.loads((ROOT / "dependencies.json").read_text(encoding="utf-8"))


def sdk_root():
    for key in ("DCL_ANDROID_SDK", "ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            return Path(os.environ[key]).expanduser().resolve()
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local")) / "Android/Sdk"
    if platform.system() == "Darwin":
        return Path.home() / "Library/Android/sdk"
    return Path.home() / "Android/Sdk"


def java_home():
    for key in ("DCL_JAVA", "JAVA_HOME"):
        if os.environ.get(key):
            return Path(os.environ[key]).expanduser().resolve()
    compiler = shutil.which("javac")
    if compiler:
        return Path(compiler).resolve().parent.parent
    if os.name == "nt":
        return (
            Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Android/Android Studio/jbr"
        )
    raise RuntimeError("JDK 17+ not found; set JAVA_HOME or DCL_JAVA")


def executable(directory, name):
    path = Path(directory) / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise RuntimeError(f"Required tool not found: {path}")
    return path


def ndk_root():
    value = os.environ.get("DCL_ANDROID_NDK") or os.environ.get("ANDROID_NDK_HOME")
    return Path(value).expanduser().resolve() if value else sdk_root() / "ndk" / CONFIG["ndk"]


def ndk_toolchain(ndk):
    host = {"Windows": "windows-x86_64", "Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(
        platform.system()
    )
    if not host:
        raise RuntimeError(f"Unsupported build host: {platform.system()}")
    return Path(ndk) / "toolchains/llvm/prebuilt" / host


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(arguments, timeout=180):
    result = subprocess.run(
        [str(value) for value in arguments],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    if result.stderr.strip():
        print(result.stderr.strip())
    return result.stdout

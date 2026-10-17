#!/usr/bin/env python3
"""Read-only integrity checks for the APK described by build/identity.json."""

import hashlib
import json
import re
import zipfile

from build_config import ROOT, sha256


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check():
    identity = json.loads((ROOT / "build/identity.json").read_text(encoding="utf-8"))
    from pathlib import Path

    apk = Path(identity["path"])
    require(sha256(apk) == identity["sha256"], "APK identity hash mismatch")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate APK archive entries")
        for name in (
            "AndroidManifest.xml",
            "classes.dex",
            "assets/backend/table-runtime",
            "META-INF/xposed/java_init.list",
            "META-INF/xposed/scope.list",
            "META-INF/licenses/NDK-NOTICE.txt",
        ):
            require(name in names, f"Missing APK input: {name}")
        helper = archive.read("assets/backend/table-runtime")
        require(
            hashlib.sha256(helper).hexdigest() == identity["native_sha256"],
            "Stale native helper packaged",
        )
        require(
            helper[:6] == b"\x7fELF\x02\x01" and helper[18:20] == b"\xb7\x00",
            "Expected AArch64 ELF64",
        )
        require(
            archive.read("META-INF/xposed/scope.list").decode().strip()
            == "com.sec.android.app.soundalive",
            "Unexpected hook scope",
        )
        require(
            archive.read("META-INF/xposed/java_init.list").decode().strip()
            == "io.github.dcl.app.SoundAliveHook",
            "Wrong hook entry",
        )
        require(
            not any(
                re.search(r"(^|/)(private|build|vendor)/|\.(p12|jks|pem|java|py)$", name)
                for name in names
            ),
            "Private/development inputs packaged",
        )
        require(
            b"Lio/github/libxposed/api/XposedModule;" in archive.read("classes.dex"),
            "Missing Xposed API reference",
        )
    print(f"Verified APK packaging, fixed scope and helper SHA256: {identity['sha256']}")


if __name__ == "__main__":
    check()

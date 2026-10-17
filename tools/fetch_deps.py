#!/usr/bin/env python3
"""Fetch the pinned compile-only API from Maven Central; verify before extraction."""

import io
import hashlib
import urllib.request
import zipfile

from build_config import DEPENDENCIES, ROOT, sha256

API = ROOT / "vendor/xposed-api-101.jar"


def fetch():
    dependency = DEPENDENCIES["libxposed_api"]
    if API.is_file() and sha256(API) == dependency["jar_sha256"]:
        print("libxposed API already verified")
        return API
    with urllib.request.urlopen(dependency["url"], timeout=30) as response:
        archive = response.read(4 * 1024 * 1024 + 1)
    if hashlib.sha256(archive).hexdigest() != dependency["aar_sha256"]:
        raise ValueError("libxposed AAR SHA256 mismatch")
    with zipfile.ZipFile(io.BytesIO(archive)) as source:
        jar = source.read("classes.jar")
    if hashlib.sha256(jar).hexdigest() != dependency["jar_sha256"]:
        raise ValueError("libxposed classes.jar SHA256 mismatch")
    API.parent.mkdir(parents=True, exist_ok=True)
    temporary = API.with_suffix(".jar.tmp")
    temporary.write_bytes(jar)
    temporary.replace(API)
    print(f"Verified libxposed API {dependency['version']}")
    return API


if __name__ == "__main__":
    fetch()

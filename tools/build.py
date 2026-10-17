#!/usr/bin/env python3
"""Build from pinned inputs. Downloads and installation are separate commands."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import zipfile

from build_config import CONFIG, DEPENDENCIES, ROOT, executable, java_home, run, sdk_root, sha256


def archive_file(archive, source, name):
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = 0o100644 << 16
    archive.writestr(entry, source.read_bytes())


def validate_paths(args):
    key, password_file = args.key.resolve(), args.password_file.resolve()
    if key == password_file:
        raise ValueError("Signing key and password must be different files")
    suffix = "-unsigned" if args.unsigned else ""
    output = (
        args.output or ROOT / "dist" / f"Dolby_Control_v{CONFIG['version_name']}{suffix}.apk"
    ).resolve()
    if output.suffix.lower() != ".apk" or output in (key, password_file):
        raise ValueError("Output must be an APK path distinct from signing material")
    for name in (
        "private",
        ".git",
        "src",
        "res",
        "metadata",
        "tools",
        "tests",
        "vendor",
        "assets",
        "LICENSES",
    ):
        if output.is_relative_to(ROOT / name):
            raise ValueError("Output cannot overwrite project inputs or private files")
    return output


def build(args):
    output = validate_paths(args)
    sdk, jdk = sdk_root(), java_home()
    tools = sdk / "build-tools" / CONFIG["build_tools"]
    android = (
        sdk
        / "platforms"
        / os.environ.get("DCL_ANDROID_PLATFORM", CONFIG["platform"])
        / "android.jar"
    )
    api = ROOT / "vendor/xposed-api-101.jar"
    if not android.is_file():
        raise RuntimeError(f"Android platform missing: {android}")
    if not api.is_file() or sha256(api) != DEPENDENCIES["libxposed_api"]["jar_sha256"]:
        raise RuntimeError("Missing/unverified compile-only API; run python tools/fetch_deps.py")
    # Always rebuild native sources; never silently package a stale helper.
    subprocess.run([sys.executable, str(ROOT / "tools/build_native.py")], check=True)
    build_dir = ROOT / "build"
    build_dir.mkdir(exist_ok=True)
    native_hash = sha256(ROOT / "assets/backend/table-runtime")
    token_hash = hashlib.sha256()
    inputs = [ROOT / "project.json", ROOT / "AndroidManifest.xml"]
    for directory in ("src", "res", "metadata"):
        inputs += [
            p
            for p in (ROOT / directory).rglob("*")
            if p.is_file() and p.name != "BuildIdentity.java"
        ]
    for path in sorted(inputs, key=lambda item: item.relative_to(ROOT).as_posix()):
        name = path.relative_to(ROOT).as_posix().encode()
        token_hash.update(len(name).to_bytes(4, "big") + name)
        token_hash.update(hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).digest())
    token_hash.update(native_hash.encode())
    token = token_hash.hexdigest()[:24]
    identity_source = build_dir / "generated/io/github/dcl/app/BuildIdentity.java"
    identity_source.parent.mkdir(parents=True, exist_ok=True)
    identity_source.write_text(
        "package io.github.dcl.app;\npublic final class BuildIdentity {\n"
        f'    public static final String TOKEN = "{token}";\n'
        f'    public static final String NATIVE_SHA256 = "{native_hash}";\n'
        "    private BuildIdentity() {}\n}\n",
        encoding="utf-8",
    )
    classes, dex = build_dir / "classes", build_dir / "dex"
    for directory in (classes, dex):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    sources = sorted(p for p in (ROOT / "src").rglob("*.java") if p.name != "BuildIdentity.java")
    sources.append(identity_source)
    java = executable(jdk / "bin", "java")
    run(
        [
            executable(jdk / "bin", "javac"),
            "--release",
            "17",
            "-encoding",
            "UTF-8",
            "-cp",
            os.pathsep.join(map(str, [android, api])),
            "-d",
            classes,
            *sources,
        ]
    )
    run(
        [
            java,
            "-cp",
            tools / "lib/d8.jar",
            "com.android.tools.r8.D8",
            "--lib",
            android,
            "--classpath",
            api,
            "--min-api",
            CONFIG["min_sdk"],
            "--output",
            dex,
            *sorted(classes.rglob("*.class")),
        ]
    )
    compiled, resources = build_dir / "resources.zip", build_dir / "resources.apk"
    aapt2 = executable(tools, "aapt2")
    run([aapt2, "compile", "--dir", ROOT / "res", "-o", compiled])
    run(
        [
            aapt2,
            "link",
            "-I",
            android,
            "--manifest",
            ROOT / "AndroidManifest.xml",
            "-o",
            resources,
            "-R",
            compiled,
            "--auto-add-overlay",
            "--version-code",
            CONFIG["version_code"],
            "--version-name",
            CONFIG["version_name"],
            "-A",
            ROOT / "assets",
        ]
    )
    unsigned, aligned = build_dir / "unsigned.apk", build_dir / "aligned.apk"
    shutil.copyfile(resources, unsigned)
    with zipfile.ZipFile(unsigned, "a") as archive:
        for path in sorted(dex.glob("*.dex")):
            archive_file(archive, path, path.name)
        for path in sorted((ROOT / "metadata").rglob("*")):
            if path.is_file():
                archive_file(archive, path, path.relative_to(ROOT / "metadata").as_posix())
        for path in sorted((ROOT / "LICENSES").glob("*")):
            archive_file(archive, path, "META-INF/licenses/" + path.name)
        for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
            archive_file(archive, ROOT / name, "META-INF/licenses/" + name)
    run([executable(tools, "zipalign"), "-f", "4", unsigned, aligned])
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.unsigned:
        shutil.copyfile(aligned, output)
    else:
        key, password_file = args.key.resolve(), args.password_file.resolve()
        if not key.exists():
            if not args.init_signing:
                raise RuntimeError(
                    "Signing key missing. Reuse your existing key, or explicitly pass --init-signing for a NEW identity"
                )
            key.parent.mkdir(parents=True, exist_ok=True)
            password_file.parent.mkdir(parents=True, exist_ok=True)
            if not password_file.exists():
                password_file.write_text(secrets.token_hex(24), encoding="ascii")
                if os.name != "nt":
                    password_file.chmod(0o600)
            run(
                [
                    executable(jdk / "bin", "keytool"),
                    "-genkeypair",
                    "-keystore",
                    key,
                    "-storetype",
                    "PKCS12",
                    "-storepass:file",
                    password_file,
                    "-alias",
                    "dolby-control",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "3072",
                    "-validity",
                    "10000",
                    "-dname",
                    "CN=Dolby Control Local",
                ]
            )
        if not password_file.is_file():
            raise RuntimeError("Signing password file missing")
        signer = tools / "lib/apksigner.jar"
        run(
            [
                java,
                "-jar",
                signer,
                "sign",
                "--ks",
                key,
                "--ks-key-alias",
                "dolby-control",
                "--ks-pass",
                "file:" + str(password_file),
                "--out",
                output,
                aligned,
            ]
        )
        verification = run([java, "-jar", signer, "verify", "--verbose", "--print-certs", output])
        (build_dir / "signature.txt").write_text(verification, encoding="utf-8")
    identity = {
        "path": str(output),
        "version": CONFIG["version_name"],
        "signed": not args.unsigned,
        "bytes": output.stat().st_size,
        "sha256": sha256(output),
        "build": token,
        "source_count": len(sources),
        "native_sha256": native_hash,
    }
    (build_dir / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
    print(json.dumps(identity, indent=2))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--unsigned", action="store_true", help="Build for CI without accessing a signing identity"
    )
    parser.add_argument(
        "--init-signing",
        action="store_true",
        help="Explicitly create a NEW local signing identity if absent",
    )
    parser.add_argument("--key", type=Path, default=ROOT / "private/signing.p12")
    parser.add_argument("--password-file", type=Path, default=ROOT / "private/signing-password.txt")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        build(args)
    except (RuntimeError, ValueError, subprocess.SubprocessError, OSError) as error:
        parser.exit(1, f"Build failed: {error}\n")


if __name__ == "__main__":
    main()

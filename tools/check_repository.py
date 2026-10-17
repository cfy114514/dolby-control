#!/usr/bin/env python3
"""Check the public source inventory without printing any matched credential data."""

from pathlib import Path
import re
import subprocess

from build_config import ROOT

FORBIDDEN_SUFFIXES = {
    ".apk",
    ".dex",
    ".so",
    ".dll",
    ".class",
    ".jar",
    ".pyc",
    ".p12",
    ".jks",
    ".pem",
    ".keystore",
    ".zip",
    ".gz",
}
FORBIDDEN_DIRECTORIES = {"private", "build", "dist", "__pycache__"}
PATTERNS = (
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b"),
    re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}\b"),
)


def check():
    result = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True)
    names = [name.decode("utf-8") for name in result.stdout.split(b"\0") if name]
    problems = []
    for name in names:
        path = Path(name)
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or any(
            part in FORBIDDEN_DIRECTORIES for part in path.parts
        ):
            problems.append(name + ": generated, signing or device artifact tracked")
        if name == "assets/backend/table-runtime":
            problems.append(name + ": generated helper tracked")
        source = ROOT / path
        if source.is_file() and any(pattern.search(source.read_bytes()) for pattern in PATTERNS):
            problems.append(name + ": credential pattern detected")
    if problems:
        raise ValueError("Public inventory rejected:\n" + "\n".join(problems))
    print(
        f"Verified {len(names)} tracked source files; no signing/device artifacts or credential patterns"
    )


if __name__ == "__main__":
    check()

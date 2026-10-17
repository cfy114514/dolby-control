"""Capture developer-only phone UI evidence into ignored build/."""

import argparse
import json
import re
import xml.etree.ElementTree as ET

from build_config import ROOT
from device_probe import adb


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action", choices=("screenshot", "hierarchy"), nargs="?", default="hierarchy"
    )
    parser.add_argument("name", nargs="?", default="phone")
    args = parser.parse_args()
    if args.action == "screenshot":
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.name):
            parser.error("Use a simple screenshot name (letters, digits, hyphens or underscores)")
        data = adb("exec-out", "screencap", "-p")
        if not data.startswith(b"\x89PNG"):
            raise ValueError("Invalid screenshot")
        destination = ROOT / "build" / f"{args.name}.png"
        destination.parent.mkdir(exist_ok=True)
        destination.write_bytes(data)
        print(destination)
    else:
        adb("shell", "uiautomator", "dump", "/data/local/tmp/dcl-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/data/local/tmp/dcl-ui.xml"))
        keys = (
            "text",
            "content-desc",
            "resource-id",
            "class",
            "bounds",
            "enabled",
            "checked",
            "clickable",
        )
        print(
            json.dumps(
                [
                    {key: node.get(key) for key in keys}
                    for node in root.iter("node")
                    if node.get("text") or node.get("content-desc")
                ],
                ensure_ascii=False,
                indent=2,
            )
        )


if __name__ == "__main__":
    main()

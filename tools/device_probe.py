"""Developer-only ADB helpers; importing this module never contacts a device."""

import json
import os
import shlex
import subprocess
import sys


def adb(*arguments, timeout=30):
    command = ["adb"]
    if os.environ.get("DCL_ADB_SERIAL"):
        command += ["-s", os.environ["DCL_ADB_SERIAL"]]
    return subprocess.run(
        command + list(arguments), capture_output=True, check=True, timeout=timeout
    ).stdout


def remote(command, root=True):
    if root:
        command = "su -c " + shlex.quote(command)
    return adb("shell", command).decode("utf-8", errors="replace")


def rpc(action, args=None, root=True):
    command = (
        "CLASSPATH=/data/local/tmp/dcl-probe.dex app_process /system/bin DeviceProbe "
        + shlex.quote(action)
    )
    if args is not None:
        command += " " + shlex.quote(json.dumps(args, separators=(",", ":")))
    value = remote(command, root)
    return json.loads(value.strip().splitlines()[-1])


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "status"
    args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else None
    print(json.dumps(rpc(action, args), ensure_ascii=False, indent=2))

from device_probe import rpc, remote, adb
from pathlib import Path
import sys
import json
import re
import time


def main():
    if not __debug__:
        raise SystemExit("Device acceptance tests require Python without -O")
    if "--execute" not in sys.argv:
        raise SystemExit(
            "This changes phone playback/settings. Read docs/TESTING.md and pass --execute to run."
        )
    sys.argv.remove("--execute")
    goal = int(sys.argv[1])
    rows = []

    def sample(label, playing=False):
        for attempt in range(8):
            value = rpc("status")
            if not playing or value.get("active"):
                break
            time.sleep(0.35)
        media = remote("dumpsys media_session")
        match = re.search(
            r"package=com.apple.android.music(.*?)(?=\n\s*Media button|\Z)", media, re.S
        )
        section = match.group(1) if match else ""
        playback = re.search(r"state=PlaybackState \{state=([A-Z]+)\(", section)
        title = re.search(r"metadata:.*description=(.*)", section)
        row = {
            "event": label,
            "status": value,
            "playback": playback.group(1) if playback else None,
            "metadata": title.group(1) if title else None,
        }
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
        assert value.get("selected_profile") == goal, f"Manual selection lost after {label}"
        if playing:
            assert row["playback"] == "PLAYING", f"Apple Music not playing after {label}"
            assert value.get("active") and value.get("profile") == goal, (
                f"Actual profile mismatch after {label}"
            )

    sample("initial", True)
    for label, key, playing in [("pause", 127, False), ("resume", 126, True), ("next", 87, True)]:
        adb("shell", "input", "keyevent", str(key))
        sample(label, playing)
    remote("am start -n com.apple.android.music/.common.MainActivity")
    sample("reopen_apple_music", True)
    remote("am force-stop com.sec.android.app.soundalive")
    remote("am start -n com.sec.android.app.soundalive/.activity.DolbyAtmosActivity")
    sample("reopen_system_dolby", True)
    dest = Path(__file__).resolve().parents[1] / "build" / f"playback-profile-{goal}.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"PASS: {len(rows)} events")


if __name__ == "__main__":
    main()

import sys

"""Exercise profile 8 on the real phone and retain DSP evidence; restore entry settings."""
from device_probe import rpc, remote
from pathlib import Path
import json


def main():
    if not __debug__:
        raise SystemExit("Device acceptance tests require Python without -O")
    if "--execute" not in sys.argv:
        raise SystemExit(
            "This changes phone playback/settings. Read docs/TESTING.md and pass --execute to run."
        )
    sys.argv.remove("--execute")
    OUT = Path(__file__).resolve().parents[1] / "build"
    OUT.mkdir(parents=True, exist_ok=True)
    before = rpc("settings")
    assert before["active"] and before["profile"] == 8, before
    assert (
        len(before["records"]) == 44
        and len([r for r in before["records"] if r["profile"] == 8]) == 8
    )
    restore = [
        {"profile": r["profile"], "key": r["key"], "value": r["current"]} for r in before["records"]
    ]
    events = []

    def receipt(label, result):
        assert result["ok"], result
        r = json.loads(remote("cat /data/adb/dolby_control_app/neutral-last.json"))
        assert r["ok"] and r["reload"]["profile_reloaded"]
        assert r["reload"]["before"]["profile"] == r["reload"]["after"]["profile"] == 8
        value = {"event": label, "result": result, "receipt": r}
        events.append(value)
        print(
            json.dumps(
                {
                    "event": label,
                    "profile": result["profile"],
                    "state": result["enhancement"],
                    "dsp_observed": r["dsp_setters_observed"],
                    "setters": r["dsp_last_setters"],
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
        return r

    try:
        preset = receipt("preset", rpc("enhancement", {"mode": "preset"}))
        assert all(r["current"] == r["original"] for r in preset["table"]["records"])
        assert preset["dsp_last_setters"]["deon"] == 1 and preset["dsp_last_setters"]["mdee"] == 1
        neutral = receipt("neutral", rpc("enhancement", {"mode": "neutral"}))
        assert neutral["dsp_setters_observed"]
        assert all(r["current"] == 0 for r in neutral["table"]["records"] if r["is_flag"])
        edits = [
            {"profile": 8, "key": "ieq-enable", "value": 1},
            {"profile": 8, "key": "dialog-enhancer-enable", "value": 1},
        ]
        custom = receipt("spatial_only_flags", rpc("edit", {"edits": edits}))
        assert custom["dsp_setters_observed"]
        assert custom["dsp_last_setters"]["ieon"] == custom["dsp_last_setters"]["deon"] == 1
        assert all(
            r["current"] == 0
            for r in custom["table"]["records"]
            if r["profile"] == 2 and r["is_flag"]
        )
        common = receipt(
            "spatial_common_and_amount",
            rpc(
                "edit",
                {
                    "edits": [
                        {"profile": 8, "key": "volume-leveler-amount", "value": 1},
                        {"profile": -1, "key": "dialog-enhancer-amount", "value": 5},
                        {"profile": -1, "key": "volume-leveler-out-target", "value": -257},
                    ]
                },
            ),
        )
        assert common["dsp_setters_observed"], common["dsp_last_setters"]
        assert common["dsp_last_setters"]["dvla"] == 1
        assert common["dsp_last_setters"]["dea"] == 5
        assert common["dsp_last_setters"]["dvlo"] == -257
        invalid = rpc("edit", {"edits": [{"profile": 6, "key": "ieq-enable", "value": 1}]})
        assert not invalid["ok"]
        invalid_value = rpc(
            "edit", {"edits": [{"profile": 8, "key": "volume-leveler-amount", "value": 10}]}
        )
        assert not invalid_value["ok"]
        events.append(
            {
                "event": "invalid_scope_and_bounds_rejected",
                "scope": invalid,
                "bounds": invalid_value,
            }
        )
    finally:
        restored = rpc("edit", {"edits": restore})
        assert restored["ok"], restored
        assert {(r["profile"], r["key"]): r["current"] for r in restored["records"]} == {
            (r["profile"], r["key"]): r["current"] for r in before["records"]
        }
        events.append({"event": "entry_settings_restored", "result": restored})
        (OUT / "spatial-live-evidence.json").write_text(
            json.dumps(events, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print("Entry settings restored", flush=True)
    print("PASS: profile 8 flags, numeric reload, independent profile 2, rejected scopes/bounds")


if __name__ == "__main__":
    main()

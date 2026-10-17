# Contributing

Run the host tests and build an unsigned APK before proposing a change. Use JDK 17+, Python 3.11+ and the pinned SDK/NDK versions in `project.json`. See the README for commands.

Keep the daily UI small. Diagnostic tools belong under `tools/`, not in the user flow. Root work must stay off the UI thread. An injected SoundAlive process must never execute `su` or receive arbitrary Root command access.

Do not weaken the fixed firmware, library, process identity, record-prefix or range checks to support another firmware. A new adapter requires evidence from that exact original library and explicit device validation. Never commit signing files, proprietary firmware, user logs or recordings.

For a behavior change, include a regression that exercises the actual implementation. Report device tests separately from host tests. A successful build does not establish DSP behavior; a table write does not establish audible output. Preserve and restore entry state during real-device experiments.

Formatting: UTF-8, LF, four-space Java/Python indentation and two-space C++ indentation. Optional Python checks: install `requirements-dev.txt`, then run `ruff check tools tests` and `ruff format --check tools tests`. Keep generated artifacts in ignored `build/`, `dist/`, `vendor/*.jar` and `assets/backend/table-runtime`. Build identity sources are generated under `build/generated/`.

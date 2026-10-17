# Security boundaries

This application intentionally requires Root and modifies a fixed set of values in the private memory of an existing audio process. It is firmware-specific and does not patch system files.

The Root socket accepts the application UID and Root as owners. Only the verified SoundAlive UID and exact process name may access its limited status/manual-selection operations. It is not a general Root shell API. Third-party apps and the ADB shell UID are not trusted. Request sizes, worker queues, execution times and response sizes are bounded.

The native adapter must verify the process start time, library identity/hash, mappings, fixed record prefixes and value ranges before writes. Concurrent changes must be detected. Rollback must not silently overwrite unrelated values or claim success after an incomplete restoration.

Signing files remain local under ignored `private/`. CI builds unsigned artifacts without signing credentials. Public source archives must not contain device evidence, firmware libraries, user paths, private keys or passwords.

Please report vulnerabilities through GitHub's private vulnerability reporting when available. Avoid posting personal device dumps or signing material in public issues.

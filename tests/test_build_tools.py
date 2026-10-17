"""Regressions for signing-file preservation and side-effect-free device tools."""

import argparse
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from build import validate_paths


class BuildPathTests(unittest.TestCase):
    def test_rejects_output_that_can_destroy_signing_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "identity.apk"
            password = Path(directory) / "password.apk"
            for output in (key, password):
                for unsigned in (True, False):
                    args = argparse.Namespace(
                        key=key, password_file=password, output=output, unsigned=unsigned
                    )
                    with self.assertRaises(ValueError):
                        validate_paths(args)

    def test_rejects_aliases_and_project_inputs(self):
        args = argparse.Namespace(
            key=ROOT / "private/key", password_file=ROOT / "private/password", unsigned=True
        )
        for output in (
            ROOT / "src/lost.apk",
            ROOT / ".git/lost.apk",
            ROOT / "private/lost.apk",
            ROOT / "README.md",
        ):
            args.output = output
            with self.assertRaises(ValueError):
                validate_paths(args)
        args.key = args.password_file
        args.output = None
        with self.assertRaises(ValueError):
            validate_paths(args)

    def test_valid_output_is_local_and_has_expected_suffix(self):
        args = argparse.Namespace(
            key=ROOT / "private/key",
            password_file=ROOT / "private/password",
            unsigned=True,
            output=None,
        )
        self.assertEqual(validate_paths(args).parent, ROOT / "dist")
        self.assertTrue(validate_paths(args).name.endswith("-unsigned.apk"))


class DeviceImportTests(unittest.TestCase):
    def test_importing_acceptance_tools_never_contacts_or_changes_phone(self):
        for name in (
            "device_probe",
            "phone_ui",
            "playback_check",
            "spatial_check",
            "lifecycle_check",
            "build_probe",
        ):
            with (
                self.subTest(tool=name),
                patch("subprocess.run", side_effect=AssertionError("Unexpected device operation")),
            ):
                spec = importlib.util.spec_from_file_location(
                    "dcl_import_test_" + name, ROOT / "tools" / (name + ".py")
                )
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)


if __name__ == "__main__":
    unittest.main()

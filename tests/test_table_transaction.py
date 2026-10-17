#!/usr/bin/env python3
"""Exercise the actual C++ transaction core through a host fixture library."""

import ctypes as C
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
IDENTITY = C.CFUNCTYPE(C.c_int, C.c_void_p)
READ = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_uint64, C.POINTER(C.c_uint32))
WRITE = C.CFUNCTYPE(C.c_int, C.c_void_p, C.c_uint64, C.c_uint32)


class Word(C.Structure):
    _fields_ = [
        ("address", C.c_uint64),
        ("original", C.c_uint32),
        ("target", C.c_uint32),
        ("minimum", C.c_int32),
        ("maximum", C.c_int32),
        ("is_flag", C.c_uint),
    ]


class Field(C.Structure):
    _fields_ = [
        ("profile", C.c_int),
        ("key", C.c_char_p),
        ("minimum", C.c_int),
        ("maximum", C.c_int),
    ]


class Selection(C.Structure):
    _fields_ = [
        ("values", C.c_uint * 256),
        ("selected", C.c_uint * 256),
        ("expected", C.c_uint * 256),
    ]


class Callbacks(C.Structure):
    _fields_ = [("context", C.c_void_p), ("identity", IDENTITY), ("read", READ), ("write", WRITE)]


class Result(C.Structure):
    _fields_ = [
        ("code", C.c_int),
        ("failed_index", C.c_uint),
        ("changed_words", C.c_uint),
        ("rollback_words", C.c_uint),
        ("rollback_complete", C.c_int),
        ("state_before", C.c_int),
        ("state_after", C.c_int),
    ]


class Fixture:
    def __init__(self, original=(1, 0, 1), current=None):
        self.words = (Word * len(original))(
            *[Word(0x1000 + index * 16, value, 0, 1, 0, 1) for index, value in enumerate(original)]
        )
        self.values = {word.address: value for word, value in zip(self.words, current or original)}
        self.before = dict(self.values)
        self.identity_valid = True
        self.fail_read = False
        self.writes = []
        self.fail_writes = set()
        self.partial_write = False
        self.partial_bytes = 2
        self.invalidate_after_write = False
        self.after_write = None
        self.after_failed_write = None
        self.functions = (IDENTITY(self.identity), READ(self.read), WRITE(self.write))
        self.callbacks = Callbacks(None, *self.functions)

    def identity(self, _):
        return int(self.identity_valid)

    def read(self, _, address, result):
        if self.fail_read:
            return 0
        result[0] = self.values[address]
        return 1

    def write(self, _, address, value):
        self.writes.append((address, value))
        if len(self.writes) in self.fail_writes:
            if self.partial_write:
                mask = (1 << (self.partial_bytes * 8)) - 1
                self.values[address] = (self.values[address] & ~mask) | (value & mask)
            if self.after_failed_write:
                self.after_failed_write(self)
            return 0
        self.values[address] = value
        if self.after_write:
            self.after_write(self)
        if self.invalidate_after_write:
            self.identity_valid = False
        return 1

    def run(self, mode):
        result = Result()
        LIB.dcl_fixture_run(
            self.words, len(self.words), mode, C.byref(self.callbacks), C.byref(result)
        )
        return result

    def batch(self, changes):
        count = len(self.words)
        values, selected, before, after = [(C.c_uint * count)() for _ in range(4)]
        for index, value in changes.items():
            values[index], selected[index] = value, 1
        result = Result()
        LIB.dcl_fixture_batch(
            self.words,
            count,
            values,
            selected,
            C.byref(self.callbacks),
            C.byref(result),
            before,
            after,
        )
        return result, list(before), list(after)

    def restore(self, changes):
        count = len(self.words)
        values, selected, expected, before, after = [(C.c_uint * count)() for _ in range(5)]
        for index, (expected_value, value) in changes.items():
            expected[index], values[index], selected[index] = expected_value, value, 1
        result = Result()
        LIB.dcl_fixture_restore(
            self.words,
            count,
            values,
            selected,
            expected,
            C.byref(self.callbacks),
            C.byref(result),
            before,
            after,
        )
        return result, list(before), list(after)


class TransactionTests(unittest.TestCase):
    def test_status_is_read_only(self):
        fixture = Fixture()
        result = fixture.run(0)
        self.assertEqual(result.code, 0)
        self.assertEqual(result.state_after, 1)
        self.assertEqual(fixture.writes, [])

    def test_on_off_and_idempotency(self):
        fixture = Fixture()
        result = fixture.run(1)
        self.assertEqual((result.code, result.changed_words, result.state_after), (0, 2, 2))
        fixture.writes.clear()
        result = fixture.run(1)
        self.assertEqual(result.changed_words, 0)
        self.assertEqual(fixture.writes, [])
        result = fixture.run(2)
        self.assertEqual((result.code, result.changed_words, result.state_after), (0, 2, 1))
        self.assertEqual(fixture.values, fixture.before)

    def test_mixed_recovery_in_both_directions(self):
        for mode in (1, 2):
            fixture = Fixture(current=(0, 0, 1))
            result = fixture.run(mode)
            self.assertEqual((result.code, result.state_before, result.changed_words), (0, 3, 1))
            self.assertEqual(result.state_after, 2 if mode == 1 else 1)

    def test_unknown_value_rejects_before_any_write(self):
        fixture = Fixture(current=(1, 0, 999))
        result = fixture.run(1)
        self.assertEqual(result.code, 4)
        self.assertEqual(fixture.writes, [])
        self.assertEqual(fixture.values, fixture.before)

    def test_short_write_rolls_back_the_failing_word_too(self):
        for length in (1, 2, 3):
            with self.subTest(length=length):
                fixture = Fixture(original=(0x11223344, 0x55667788))
                fixture.fail_writes = {2}
                fixture.partial_write = True
                fixture.partial_bytes = length
                result = fixture.run(1)
                self.assertEqual(
                    (result.code, result.changed_words, result.rollback_words), (6, 1, 2)
                )
                self.assertTrue(result.rollback_complete)
                self.assertEqual(fixture.values, fixture.before)

    def test_failure_restores_mixed_entry_state(self):
        fixture = Fixture(original=(1, 1, 1), current=(0, 1, 1))
        fixture.fail_writes = {2}
        result = fixture.run(1)
        self.assertEqual((result.code, result.state_before, result.state_after), (6, 3, 3))
        self.assertEqual(fixture.values, fixture.before)

    def test_identity_change_prevents_further_writes_and_stale_rollback(self):
        fixture = Fixture()
        fixture.invalidate_after_write = True
        result = fixture.run(1)
        self.assertEqual(result.code, 2)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(len(fixture.writes), 1)
        self.assertEqual(result.state_after, 0)

    def test_rollback_failure_is_explicit(self):
        fixture = Fixture()
        fixture.fail_writes = {2, 3, 4}
        result = fixture.run(1)
        self.assertEqual(result.code, 6)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(result.state_after, 0)

    def test_concurrent_change_to_untouched_word_is_not_hidden_by_rollback(self):
        fixture = Fixture()
        fixture.after_write = lambda f: f.values.__setitem__(f.words[1].address, 777)
        result = fixture.run(1)
        self.assertEqual(result.code, 5)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(result.state_after, 0)

    def test_rollback_preserves_a_later_writers_value(self):
        fixture = Fixture(original=(3,))
        fixture.words[0].minimum, fixture.words[0].maximum = 0, 9
        fixture.after_write = lambda f: f.values.__setitem__(f.words[0].address, 9)
        result = fixture.run(1)
        self.assertEqual(result.code, 7)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(result.state_after, 0)
        self.assertEqual(fixture.values[fixture.words[0].address], 9)
        self.assertEqual(len(fixture.writes), 1)

    def test_failed_write_rollback_preserves_an_unrelated_later_value(self):
        fixture = Fixture(original=(3,))
        fixture.words[0].minimum, fixture.words[0].maximum = 0, 9
        fixture.fail_writes = {1}
        fixture.after_failed_write = lambda f: f.values.__setitem__(f.words[0].address, 9)
        result = fixture.run(1)
        self.assertEqual((result.code, result.rollback_words), (6, 0))
        self.assertFalse(result.rollback_complete)
        self.assertEqual(result.state_after, 0)
        self.assertEqual(fixture.values[fixture.words[0].address], 9)
        self.assertEqual(len(fixture.writes), 1)

    def test_rollback_does_not_write_when_current_value_cannot_be_read(self):
        fixture = Fixture(original=(1,))
        fixture.after_write = lambda f: setattr(f, "fail_read", True)
        result = fixture.run(1)
        self.assertEqual(result.code, 7)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(len(fixture.writes), 1)

    def test_already_restored_word_needs_no_rollback_write(self):
        fixture = Fixture(original=(1,))
        fixture.after_write = lambda f: f.values.__setitem__(f.words[0].address, 1)
        result = fixture.run(1)
        self.assertEqual((result.code, result.rollback_words, result.state_after), (7, 0, 1))
        self.assertTrue(result.rollback_complete)
        self.assertEqual(len(fixture.writes), 1)

    def test_preflight_read_failure_never_writes(self):
        fixture = Fixture()
        fixture.fail_read = True
        self.assertEqual(fixture.run(1).code, 3)
        self.assertEqual(fixture.writes, [])

    def test_duplicate_addresses_rejected(self):
        fixture = Fixture()
        fixture.words[2].address = fixture.words[0].address
        self.assertEqual(fixture.run(1).code, 1)
        self.assertEqual(fixture.writes, [])

    def test_custom_flag_enable_from_zero_and_entry_snapshot(self):
        fixture = Fixture(original=(1, 0, 1), current=(0, 0, 0))
        for word in fixture.words:
            word.minimum, word.maximum = 0, 1
        result, before, after = fixture.batch({1: 1})
        self.assertEqual((result.code, result.changed_words, result.state_after), (0, 1, 4))
        self.assertEqual(before, [0, 0, 0])
        self.assertEqual(after, [0, 1, 0])
        self.assertEqual(fixture.run(0).state_after, 4)
        result, _, _ = fixture.batch(dict(enumerate(before)))
        self.assertEqual(result.code, 0)
        self.assertEqual(list(fixture.values.values()), before)

    def test_batch_bounds_preflight_before_any_mutation(self):
        for value in (-1, 2, 2147483647):
            fixture = Fixture()
            for word in fixture.words:
                word.minimum, word.maximum = 0, 1
            result, _, _ = fixture.batch({0: 0, 1: value})
            self.assertEqual(result.code, 1)
            self.assertEqual(fixture.writes, [])

    def test_custom_batch_failure_restores_actual_custom_entry(self):
        fixture = Fixture(original=(1, 0, 1), current=(1, 1, 1))
        for word in fixture.words:
            word.minimum, word.maximum = 0, 1
        fixture.fail_writes = {2}
        result, before, after = fixture.batch({0: 0, 1: 0})
        self.assertEqual((result.code, result.state_before, result.state_after), (6, 4, 4))
        self.assertEqual(before, [1, 1, 1])
        self.assertEqual(after, before)
        self.assertEqual(fixture.values, fixture.before)

    def test_on_preserves_numbers_and_off_restores_defaults(self):
        fixture = Fixture(original=(1, 4), current=(1, 9))
        fixture.words[0].minimum, fixture.words[0].maximum = 0, 1
        fixture.words[1].minimum, fixture.words[1].maximum, fixture.words[1].is_flag = 0, 9, 0
        result = fixture.run(1)
        self.assertEqual((result.code, result.changed_words, result.state_after), (0, 1, 2))
        self.assertEqual(fixture.values[fixture.words[1].address], 9)
        result = fixture.run(2)
        self.assertEqual((result.code, result.changed_words, result.state_after), (0, 2, 1))
        self.assertEqual(fixture.values[fixture.words[1].address], 4)

    def test_signed_numeric_batch_and_bounds(self):
        fixture = Fixture(original=(1, 0))
        fixture.words[1].minimum, fixture.words[1].maximum, fixture.words[1].is_flag = -640, 0, 0
        result, _, after = fixture.batch({1: -256})
        self.assertEqual(result.code, 0)
        self.assertEqual(C.c_int32(after[1]).value, -256)
        result, _, _ = fixture.batch({1: -641})
        self.assertEqual(result.code, 1)

    def test_conditional_restore_changes_only_matching_selected_words(self):
        fixture = Fixture(original=(1, 0, 1), current=(0, 0, 0))
        for word in fixture.words:
            word.minimum, word.maximum = 0, 1
        result, before, after = fixture.restore({0: (0, 1)})
        self.assertEqual((result.code, result.changed_words), (0, 1))
        self.assertEqual(before, [0, 0, 0])
        self.assertEqual(after, [1, 0, 0])
        self.assertEqual(fixture.writes, [(fixture.words[0].address, 1)])

    def test_conditional_restore_preflights_all_expected_values_before_writing(self):
        fixture = Fixture(original=(1, 0, 1), current=(0, 0, 1))
        for word in fixture.words:
            word.minimum, word.maximum = 0, 1
        result, _, _ = fixture.restore({0: (0, 1), 2: (0, 1)})
        self.assertEqual((result.code, result.failed_index), (5, 2))
        self.assertTrue(result.rollback_complete)
        self.assertEqual(result.changed_words, 0)
        self.assertEqual(fixture.writes, [])
        self.assertEqual(fixture.values, fixture.before)

    def test_conditional_restore_rejects_out_of_range_expected_before_any_read(self):
        fixture = Fixture()
        for word in fixture.words:
            word.minimum, word.maximum = 0, 1
        fixture.fail_read = True
        result, _, _ = fixture.restore({0: (2, 1)})
        self.assertEqual(result.code, 1)
        self.assertEqual(fixture.writes, [])

    def test_conditional_restore_detects_a_later_conflict_and_preserves_it(self):
        fixture = Fixture(original=(3, 3))
        for word in fixture.words:
            word.minimum, word.maximum = 0, 9
        fixture.after_write = lambda f: f.values.__setitem__(f.words[1].address, 9)
        result, _, _ = fixture.restore({0: (3, 0), 1: (3, 0)})
        self.assertEqual(result.code, 5)
        self.assertFalse(result.rollback_complete)
        self.assertEqual(fixture.values[fixture.words[0].address], 3)
        self.assertEqual(fixture.values[fixture.words[1].address], 9)


class ArgumentTests(unittest.TestCase):
    def parse(self, arguments, fields=None):
        values = (
            fields
            if fields is not None
            else [
                Field(0, b"ieon", 0, 1),
                Field(-1, b"dvli", -640, 0),
                Field(2, b"dvla", 0, 9),
                Field(8, b"ieon", 0, 1),
                Field(8, b"dvla", 0, 9),
            ]
        )
        fields = (Field * len(values))(*values)
        argv = (C.c_char_p * len(arguments))(
            *[arg.encode("utf-8") if arg is not None else None for arg in arguments]
        )
        selection = Selection()
        result = LIB.dcl_fixture_parse(
            len(arguments), argv, fields, len(fields), C.byref(selection)
        )
        return result, selection

    def test_valid_batch_scope_and_signed_values(self):
        result, selected = self.parse(
            ["tool", "set", "0", "ieon", "1", "-1", "dvli", "-256", "2", "dvla", "9"]
        )
        self.assertEqual(result, 0)
        self.assertEqual(list(selected.selected)[:3], [1, 1, 1])
        self.assertEqual(C.c_int32(selected.values[1]).value, -256)

    def test_invalid_arguments_never_choose_a_field(self):
        for tail in (
            [],
            ["0", "ieon"],
            ["8", "dom", "1"],
            ["-2", "dvli", "0"],
            ["0", "dom", "1"],
            ["0", "ieon", "2"],
            ["-1", "dvli", "-641"],
            ["-1", "dvli", "1"],
            ["2", "dvla", "10"],
            ["0", "ieon", "$(id)"],
            ["0", "ieon", "01"],
            ["0", "ieon", "+1"],
            ["0", "ieon", "1", "0", "ieon", "0"],
            ["0", "ieon", "2147483648"],
        ):
            with self.subTest(tail=tail):
                result, selected = self.parse(["tool", "set"] + tail)
                self.assertNotEqual(result, 0)
                self.assertFalse(any(selected.selected))
                self.assertFalse(any(selected.values))

    def test_later_error_discards_the_entire_parsed_batch(self):
        for tail in (["8", "dvla", "10"], ["7", "ieon", "1"], ["0", "unknown", "1"]):
            with self.subTest(tail=tail):
                result, selected = self.parse(["tool", "set", "0", "ieon", "1"] + tail)
                self.assertNotEqual(result, 0)
                self.assertFalse(any(selected.selected))
                self.assertFalse(any(selected.values))

    def test_null_arguments_are_rejected(self):
        for index in range(1, 5):
            arguments = ["tool", "set", "0", "ieon", "1"]
            arguments[index] = None
            with self.subTest(index=index):
                result, selected = self.parse(arguments)
                self.assertEqual(result, 1)
                self.assertFalse(any(selected.selected))

    def test_duplicate_matching_field_metadata_is_rejected(self):
        result, selected = self.parse(
            ["tool", "set", "0", "ieon", "1"], [Field(0, b"ieon", 0, 1), Field(0, b"ieon", 0, 1)]
        )
        self.assertEqual(result, 6)
        self.assertFalse(any(selected.selected))

    def test_restore_parser_preserves_scope_expected_and_signed_values(self):
        result, selected = self.parse(
            ["tool", "restore", "8", "ieon", "1", "0", "-1", "dvli", "-257", "-256"]
        )
        self.assertEqual(result, 0)
        self.assertEqual(list(selected.selected)[:5], [0, 1, 0, 1, 0])
        self.assertEqual(C.c_int32(selected.expected[1]).value, -257)
        self.assertEqual(C.c_int32(selected.values[1]).value, -256)
        self.assertEqual((selected.expected[3], selected.values[3]), (1, 0))

    def test_restore_parser_rejects_partial_duplicate_or_out_of_bounds_batch(self):
        for tail in (
            [],
            ["8", "ieon", "1"],
            ["8", "ieon", "2", "0"],
            ["8", "dvla", "0", "10"],
            ["-1", "dvli", "-641", "-256"],
            ["7", "ieon", "1", "0"],
            ["8", "ieon", "1", "0", "8", "ieon", "0", "1"],
        ):
            with self.subTest(tail=tail):
                result, selected = self.parse(["tool", "restore"] + tail)
                self.assertNotEqual(result, 0)
                self.assertFalse(any(selected.selected))
                self.assertFalse(any(selected.values))
                self.assertFalse(any(selected.expected))

    def test_profile8_flag_and_amount_are_independent_of_music(self):
        result, selected = self.parse(["tool", "set", "8", "ieon", "1", "8", "dvla", "9"])
        self.assertEqual(result, 0)
        self.assertEqual(list(selected.selected)[:5], [0, 0, 0, 1, 1])
        self.assertEqual(list(selected.values)[3:5], [1, 9])
        for key, value in [("ieon", "-1"), ("ieon", "2"), ("dvla", "-1"), ("dvla", "10")]:
            with self.subTest(key=key, value=value):
                self.assertNotEqual(self.parse(["tool", "set", "8", key, value])[0], 0)

    def test_unsupported_profiles_rejected_even_if_field_metadata_contains_them(self):
        # This tests the profile gate itself, not merely an absent field lookup.
        for profile in (-2, 4, 5, 6, 7, 9, 10, 2147483647):
            with self.subTest(profile=profile):
                result, selected = self.parse(
                    ["tool", "set", str(profile), "ieon", "1"], [Field(profile, b"ieon", 0, 1)]
                )
                self.assertEqual(result, 2)
                self.assertFalse(any(selected.selected))

    def test_all_exact_allowed_profiles_accept_matching_fields(self):
        for profile in (-1, 0, 1, 2, 3, 8):
            with self.subTest(profile=profile):
                result, selected = self.parse(
                    ["tool", "set", str(profile), "ieon", "1"], [Field(profile, b"ieon", 0, 1)]
                )
                self.assertEqual(result, 0)
                self.assertEqual(selected.selected[0], 1)


def setUpModule():
    global LIB, TEMPORARY
    TEMPORARY = tempfile.TemporaryDirectory(prefix="dcl-table-fixture.")
    library = Path(TEMPORARY.name) / (
        "table_fixture.dll" if os.name == "nt" else "table_fixture.so"
    )
    try:
        subprocess.run(
            [
                sys.executable,
                str(ROOT / "tools/build_native.py"),
                "--fixture-library",
                str(library),
            ],
            check=True,
        )
        LIB = C.CDLL(str(library))
        LIB.dcl_fixture_run.argtypes = [
            C.POINTER(Word),
            C.c_uint,
            C.c_int,
            C.POINTER(Callbacks),
            C.POINTER(Result),
        ]
        LIB.dcl_fixture_run.restype = None
        LIB.dcl_fixture_batch.argtypes = [
            C.POINTER(Word),
            C.c_uint,
            C.POINTER(C.c_uint),
            C.POINTER(C.c_uint),
            C.POINTER(Callbacks),
            C.POINTER(Result),
            C.POINTER(C.c_uint),
            C.POINTER(C.c_uint),
        ]
        LIB.dcl_fixture_batch.restype = None
        LIB.dcl_fixture_restore.argtypes = [
            C.POINTER(Word),
            C.c_uint,
            C.POINTER(C.c_uint),
            C.POINTER(C.c_uint),
            C.POINTER(C.c_uint),
            C.POINTER(Callbacks),
            C.POINTER(Result),
            C.POINTER(C.c_uint),
            C.POINTER(C.c_uint),
        ]
        LIB.dcl_fixture_restore.restype = None
        LIB.dcl_fixture_parse.argtypes = [
            C.c_int,
            C.POINTER(C.c_char_p),
            C.POINTER(Field),
            C.c_uint,
            C.POINTER(Selection),
        ]
        LIB.dcl_fixture_parse.restype = C.c_int
    except BaseException:
        TEMPORARY.cleanup()
        raise


def tearDownModule():
    # Windows keeps a loaded DLL locked; unload before removing its directory.
    import _ctypes

    if os.name == "nt":
        _ctypes.FreeLibrary(LIB._handle)
    else:
        _ctypes.dlclose(LIB._handle)
    TEMPORARY.cleanup()


if __name__ == "__main__":
    unittest.main(verbosity=2)

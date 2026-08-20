#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from release_version import parse_version, validate_candidate  # noqa: E402


class ReleaseVersionTest(unittest.TestCase):
    def test_accepts_canonical_version(self) -> None:
        self.assertEqual((0, 2, 0), parse_version("0.2.0"))

    def test_rejects_noncanonical_names(self) -> None:
        for value in ("v0.2.0", "01.2.0", "0.2", "0.2.0-beta", "0.2.0+build"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_version(value)

    def test_enforces_android_version_limits(self) -> None:
        for value in ("2101.0.0", "1.1000.0", "1.0.1000"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_version(value)

    def test_must_exceed_greatest_valid_tag(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be higher"):
            validate_candidate("0.1.6", ["v0.1.5", "v0.1.6", "vnext"])
        self.assertEqual(
            ("0.2.0", "v0.2.0", "v0.1.6"),
            validate_candidate("0.2.0", ["v0.1.5", "v0.1.6", "vnext"]),
        )

    def test_detects_concurrently_created_tag(self) -> None:
        with self.assertRaisesRegex(ValueError, "must be higher"):
            validate_candidate("0.2.0", ["v0.1.6", "v0.2.0"])


if __name__ == "__main__":
    unittest.main()

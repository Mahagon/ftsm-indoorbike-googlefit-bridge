#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("change_scope.py")
SPEC = importlib.util.spec_from_file_location("change_scope", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CHANGE_SCOPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHANGE_SCOPE)


class ChangeScopeTest(unittest.TestCase):
    def assert_scopes(self, path: str, *enabled: str) -> None:
        expected = {
            "production": False,
            "android_ci": False,
            "actions": False,
            "dependencies": False,
        }
        for scope in enabled:
            expected[scope] = True
        self.assertEqual(CHANGE_SCOPE.classify([path]), expected)

    def test_representative_paths(self) -> None:
        cases = {
            "README.md": (),
            "PRIVACY.md": (),
            ".github/workflows/codeql.yml": ("actions",),
            ".github/workflows/ci.yml": ("android_ci", "actions"),
            ".github/scripts/android_smoke_test.sh": ("android_ci",),
            ".github/scripts/change_scope.py": ("actions",),
            "app/src/main/java/example/Main.kt": ("production", "android_ci"),
            "app/src/main/res/values/strings.xml": ("production", "android_ci"),
            "app/src/test/java/example/MainTest.kt": ("android_ci",),
            "app/src/androidTest/java/example/SmokeTest.kt": ("android_ci",),
            "app/lint.xml": ("android_ci",),
            "app/build.gradle.kts": (
                "production",
                "android_ci",
                "dependencies",
            ),
            "build.gradle.kts": ("production", "android_ci", "dependencies"),
            "gradle/libs.versions.toml": (
                "production",
                "android_ci",
                "dependencies",
            ),
            "gradle/wrapper/gradle-wrapper.properties": (
                "production",
                "android_ci",
                "dependencies",
            ),
            "gradlew": ("production", "android_ci", "dependencies"),
            ".github/workflows/dependency-review.yml": (
                "actions",
                "dependencies",
            ),
        }
        for path, enabled in cases.items():
            with self.subTest(path=path):
                self.assert_scopes(path, *enabled)

    def test_mixed_paths_union_scopes(self) -> None:
        self.assertEqual(
            CHANGE_SCOPE.classify(
                ["README.md", ".github/workflows/codeql.yml", "app/src/test/Test.kt"]
            ),
            {
                "production": False,
                "android_ci": True,
                "actions": True,
                "dependencies": False,
            },
        )

    def test_windows_separators_are_normalized(self) -> None:
        self.assert_scopes(
            r"app\src\main\AndroidManifest.xml", "production", "android_ci"
        )


if __name__ == "__main__":
    unittest.main()

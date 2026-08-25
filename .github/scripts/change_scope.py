#!/usr/bin/env python3
"""Classify changed repository paths for GitHub Actions workflow routing."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Iterable


GRADLE_INPUTS = {
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
}


def _normalize(path: str) -> str:
    normalized = path.strip().replace("\\", "/")
    return normalized[2:] if normalized.startswith("./") else normalized


def _is_gradle_input(path: str) -> bool:
    return path in GRADLE_INPUTS or path.startswith("gradle/")


def classify(paths: Iterable[str]) -> dict[str, bool]:
    """Return the workflow scopes affected by *paths*."""

    scopes = {
        "production": False,
        "android_ci": False,
        "actions": False,
        "dependencies": False,
    }

    for raw_path in paths:
        path = _normalize(raw_path)
        if not path:
            continue

        gradle_input = _is_gradle_input(path)
        production = path.startswith("app/src/main/") or gradle_input
        android_ci = (
            path.startswith("app/")
            or gradle_input
            or path == ".github/workflows/ci.yml"
            or path == ".github/scripts/android_smoke_test.sh"
        )
        actions = (
            path.startswith(".github/workflows/")
            or path.startswith(".github/actions/")
            or path in {
                ".github/scripts/change_scope.py",
                ".github/scripts/change_scope_test.py",
            }
        )
        dependencies = gradle_input or path in {
            ".github/workflows/dependency-review.yml",
            ".github/workflows/dependency-submission.yml",
        }

        scopes["production"] |= production
        scopes["android_ci"] |= android_ci
        scopes["actions"] |= actions
        scopes["dependencies"] |= dependencies

    return scopes


def _write_github_output(output_path: str, scopes: dict[str, bool]) -> None:
    with Path(output_path).open("a", encoding="utf-8") as output:
        for name, enabled in scopes.items():
            output.write(f"{name}={str(enabled).lower()}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--all",
        action="store_true",
        help="Enable every scope (used by scheduled and manual analysis).",
    )
    parser.add_argument(
        "--github-output",
        metavar="PATH",
        help="Append scope values to a GitHub Actions output file.",
    )
    args = parser.parse_args()

    scopes = (
        {name: True for name in classify(()).keys()}
        if args.all
        else classify(sys.stdin)
    )
    if args.github_output:
        _write_github_output(args.github_output, scopes)
    print(json.dumps(scopes, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

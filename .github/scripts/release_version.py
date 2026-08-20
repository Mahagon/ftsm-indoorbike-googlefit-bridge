#!/usr/bin/env python3
"""Validate release branch versions against repository release tags."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections.abc import Iterable

VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
MAX_VERSION = (2100, 999, 999)


def parse_version(value: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise ValueError(
            f"'{value}' is not a valid release branch. Use MAJOR.MINOR.PATCH "
            "without 'v', suffixes, metadata, or leading zeros."
        )
    version = tuple(int(part) for part in match.groups())
    if any(value > limit for value, limit in zip(version, MAX_VERSION)):
        raise ValueError(
            f"'{value}' exceeds the supported limits: major <= 2100, "
            "minor <= 999, patch <= 999."
        )
    return version


def format_version(version: tuple[int, int, int]) -> str:
    return ".".join(str(part) for part in version)


def valid_release_versions(tags: Iterable[str]) -> list[tuple[int, int, int]]:
    versions = []
    for tag in tags:
        if not tag.startswith("v"):
            continue
        try:
            versions.append(parse_version(tag[1:]))
        except ValueError:
            continue
    return versions


def validate_candidate(candidate: str, tags: Iterable[str]) -> tuple[str, str, str]:
    version = parse_version(candidate)
    tag_list = list(tags)
    current = max(valid_release_versions(tag_list), default=(0, 0, 0))
    if version <= current:
        raise ValueError(
            f"Release version {candidate} must be higher than current release "
            f"v{format_version(current)}."
        )
    tag = f"v{candidate}"
    if tag in tag_list:
        raise ValueError(f"Release tag {tag} already exists.")
    return candidate, tag, f"v{format_version(current)}"


def git_tags() -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list"], check=True, capture_output=True, text=True
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("candidate", help="Release branch name in MAJOR.MINOR.PATCH form")
    parser.add_argument("--format", choices=("human", "github"), default="human")
    args = parser.parse_args()
    try:
        version, tag, current = validate_candidate(args.candidate, git_tags())
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    if args.format == "github":
        print(f"version={version}")
        print(f"tag={tag}")
        print(f"current={current}")
    else:
        print(f"Release branch {version} is valid (current: {current}, tag: {tag}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Validate the Android versionName/versionCode contract."""

from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "app" / "build.gradle"
VERSION_RE = re.compile(r"versionName\s+['\"](\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?['\"]")
CODE_RE = re.compile(r"versionCode\s+(\d+)")
TAG_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")


def fail(message):
    raise SystemExit("version check failed: " + message)


def main():
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    version_match = VERSION_RE.search(text)
    code_match = CODE_RE.search(text)
    if not version_match or not code_match:
        fail("versionName/versionCode not found in app/build.gradle")

    major, minor, patch = (int(version_match.group(i)) for i in range(1, 4))
    prerelease = version_match.group(4)
    version_code = int(code_match.group(1))
    build = version_code % 100
    expected = major * 1_000_000 + minor * 10_000 + patch * 100 + build
    if version_code != expected:
        fail(f"versionCode={version_code} does not match expected {expected}")

    if build < 0 or build > 99:
        fail("BUILD component must be between 0 and 99")

    tag = None
    if len(sys.argv) > 1:
        tag = sys.argv[1]
    else:
        try:
            tag = subprocess.check_output(
                ["git", "describe", "--tags", "--exact-match"],
                cwd=ROOT,
                stderr=subprocess.DEVNULL,
                text=True,
            ).strip()
        except (OSError, subprocess.CalledProcessError):
            tag = ""

    if tag:
        tag_match = TAG_RE.fullmatch(tag)
        if not tag_match:
            fail(f"tag {tag!r} is not a SemVer release tag")
        tag_parts = tuple(tag_match.group(i) or "" for i in range(1, 5))
        source_parts = (str(major), str(minor), str(patch), prerelease or "")
        if tag_parts != source_parts:
            fail(f"tag {tag!r} does not match versionName")

    suffix = f"-{prerelease}" if prerelease else ""
    print(f"version_ok name={major}.{minor}.{patch}{suffix} code={version_code} build={build}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Fail CI when common live API-token formats are embedded in repository text.

The scanner intentionally reports only the file, line, and credential family; it
never writes a matched token or surrounding source line to stdout/stderr.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {
    ".c", ".cc", ".cpp", ".gradle", ".h", ".html", ".java", ".js",
    ".json", ".kt", ".kts", ".md", ".m", ".mm", ".properties", ".py",
    ".rs", ".sh", ".swift", ".toml", ".ts", ".txt", ".xml", ".yaml",
    ".yml",
}
PATTERNS = (
    ("OpenAI/Anthropic-style token", re.compile(r"\bsk-[A-Za-z0-9_-]{24,}\b")),
    ("OpenAI-compatible provider token", re.compile(r"\b(?:gsk_|nvapi-|sk_)[A-Za-z0-9_-]{24,}\b")),
    ("Google API token", re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b")),
    ("GitHub access token", re.compile(r"\b(?:ghp_|github_pat_)[A-Za-z0-9_]{30,}\b")),
)


def repository_text_files() -> list[Path]:
    tracked_and_untracked = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout
    paths: list[Path] = []
    for raw_path in tracked_and_untracked.split(b"\0"):
        if not raw_path:
            continue
        relative = Path(raw_path.decode("utf-8", errors="surrogateescape"))
        if relative.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if any(part in {".git", ".gradle", "build", "node_modules"} for part in relative.parts):
            continue
        path = ROOT / relative
        if path.is_file():
            paths.append(path)
    return paths


def scan_file(path: Path) -> list[tuple[int, str]]:
    findings: list[tuple[int, str]] = []
    for line_number, line in enumerate(path.read_text(errors="ignore").splitlines(), 1):
        for family, pattern in PATTERNS:
            if pattern.search(line):
                findings.append((line_number, family))
    return findings


def main() -> int:
    scanned = 0
    findings: list[tuple[Path, int, str]] = []
    for path in repository_text_files():
        scanned += 1
        for line_number, family in scan_file(path):
            findings.append((path, line_number, family))

    if findings:
        for path, line_number, family in findings:
            print(f"{path.relative_to(ROOT)}:{line_number}: possible {family} (value redacted)", file=sys.stderr)
        print(f"Credential scan failed: {len(findings)} candidate(s); values were not printed.", file=sys.stderr)
        return 1

    print(f"Credential scan passed: {scanned} text files checked; no candidate values printed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

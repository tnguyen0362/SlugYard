#!/usr/bin/env python3
"""Check product identity and staged-secret boundaries without reading secrets."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PRODUCT_TEXT_SUFFIXES = {".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".md", ".py", ".json"}
ALLOWED_TEXT_FILES = {"NOTICE", "LICENSE"}
ALLOWED_COMPATIBILITY_FILES = {
    "app/src/main/java/com/sluggyard/tv/data/local/PlayerSettingsDataStore.kt",
    "app/src/main/java/com/sluggyard/tv/data/remote/dto/PlaybackIssueReportDto.kt",
}
ALLOWED_FIXTURE_PARTS = {"TraktPublicListSourceResolverTest.kt"}
ALLOWED_CHECKER_FILES = {
    "scripts/check_repository_readiness.py",
    "scripts/test_check_repository_readiness.py",
}
ALLOWED_UPSTREAM_LICENSE_FILES = {
    "ffmpeg-decoder-downmix/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java",
    "ffmpeg-decoder-downmix/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java",
}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem"}
SECRET_NAMES = {"local.properties", "local.dev.properties", ".env", ".supabase_db.env"}
IGNORED_PARTS = {".git", "build", ".gradle", ".kotlin", ".tmp", ".idea", ".superpowers", ".orchestrator", ".agents"}
SCANNED_ROOTS = {
    ".github",
    "app",
    "baselineprofile",
    "ffmpeg-decoder-downmix",
    "playflix-media3-mods",
    "scripts",
    "README.md",
    "NOTICE",
    "LICENSE",
    "PRODUCT.md",
    "BEHAVIOR_SPEC.md",
    "PUREFIRE_ADDON_CONFIG.md",
    "SUPABASE_SETUP.md",
    "test_supabase.py",
}
LEGACY_BRAND = "nuvio"


def relative(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def check_text(text: str) -> list[str]:
    if LEGACY_BRAND in text.lower():
        return ["product-facing Nuvio reference"]
    return []


def check_path(path: Path) -> list[str]:
    name = path.name.lower()
    if name in {item.lower() for item in SECRET_NAMES} or path.suffix.lower() in SECRET_SUFFIXES:
        return ["secret path"]
    return []


def check_file(path: Path, text: str | None = None) -> list[str]:
    rel = relative(path)
    if path.name in ALLOWED_TEXT_FILES or rel in ALLOWED_COMPATIBILITY_FILES:
        return []
    if rel in ALLOWED_CHECKER_FILES or rel in ALLOWED_UPSTREAM_LICENSE_FILES:
        return []
    if any(part in path.name for part in ALLOWED_FIXTURE_PARTS):
        return []
    if path.suffix.lower() not in PRODUCT_TEXT_SUFFIXES:
        return []
    contents = text if text is not None else path.read_text(encoding="utf-8", errors="replace")
    return check_text(contents)


def should_skip(path: Path) -> bool:
    return any(part in IGNORED_PARTS for part in path.parts)


def workspace_paths() -> list[Path]:
    paths: list[Path] = []
    for root in SCANNED_ROOTS:
        candidate = ROOT / root
        if candidate.is_file():
            paths.append(candidate)
        elif candidate.is_dir():
            paths.extend(path for path in candidate.rglob("*") if path.is_file() and not should_skip(path))
    return paths


def staged_paths() -> list[Path]:
    result = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged", action="store_true", help="Check staged paths instead of the workspace")
    args = parser.parse_args()

    paths = staged_paths() if args.staged else workspace_paths()
    findings: list[tuple[str, str]] = []
    skipped_secrets: list[str] = []
    for path in paths:
        path_findings = check_path(path)
        if path_findings:
            if args.staged:
                findings.extend((relative(path), item) for item in path_findings)
            else:
                skipped_secrets.append(relative(path))
            continue
        try:
            file_findings = check_file(path)
        except OSError as exc:
            findings.append((relative(path), f"unreadable file: {exc}"))
            continue
        findings.extend((relative(path), item) for item in file_findings)

    for path, reason in findings:
        print(f"ERROR {path}: {reason}")
    for path in sorted(skipped_secrets):
        print(f"INFO {path}: local secret excluded from content scan")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())

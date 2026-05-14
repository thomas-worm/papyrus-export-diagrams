#!/usr/bin/env python3
# Copyright (c) 2026 Thomas Worm
# SPDX-License-Identifier: MIT

"""Verifies that a directory of exported Papyrus diagrams matches the
expectations for a given format and naming mode.

Used by ``.github/workflows/test-feature-matrix.yml`` to check every
combination of ``--format`` and ``--naming`` the export action
supports.

The script runs the following checks:

  1. The output directory contains exactly ``--exported-count`` files.
  2. Every file's extension matches the chosen format (``--format``).
  3. Every file's first bytes match the magic-bytes signature for that
     format (catches corrupted / wrong-content outputs even when the
     extension is right).
  4. Every file is larger than 100 bytes (catches silently truncated
     exports).
  5. Every filename's stem matches the regex for the chosen naming
     mode (``--naming``).

Exits 0 on success. On the first failed check, prints a
``::error::``-prefixed message that GitHub Actions surfaces as an
annotation in the run UI, then exits with code 1.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import NoReturn

#: How many diagrams the bundled test model contains.
EXPECTED_DIAGRAM_COUNT: int = 2

#: Lowercase file extension for each supported export format.
FORMAT_EXTENSION: dict[str, str] = {
    "SVG":  "svg",
    "PNG":  "png",
    "JPEG": "jpg",
    "BMP":  "bmp",
    "GIF":  "gif",
}

#: Acceptable magic-byte prefixes for each supported export format.
#: A file is accepted when its first bytes start with any entry in
#: this list.
FORMAT_MAGIC: dict[str, list[bytes]] = {
    "SVG":  [b"<?xml", b"<svg"],
    "PNG":  [b"\x89PNG\r\n\x1a\n"],
    "JPEG": [b"\xff\xd8\xff"],
    "BMP":  [b"BM"],
    "GIF":  [b"GIF87a", b"GIF89a"],
}

#: Filename stems must match these regexes for each naming mode.
NAMING_PATTERN: dict[str, re.Pattern[str]] = {
    "xmiId": re.compile(r"^_[A-Za-z0-9_-]+$"),
    "name":  re.compile(r"^[A-Za-z][A-Za-z0-9._-]*$"),
}

#: Minimum acceptable file size in bytes. Anything below this is
#: assumed to be a silently-truncated export and treated as a failure.
MINIMUM_FILE_SIZE: int = 100


def fail(message: str) -> NoReturn:
    """Print ``message`` as a GitHub Actions error annotation and exit
    with status 1.
    """
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def parse_arguments() -> argparse.Namespace:
    """Parses the command-line arguments. See module docstring."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--output-dir",
        required=True,
        help="Directory containing the exported image files")
    parser.add_argument(
        "--format",
        required=True,
        choices=sorted(FORMAT_EXTENSION),
        help="Image format that was passed to the export action")
    parser.add_argument(
        "--naming",
        required=True,
        choices=sorted(NAMING_PATTERN),
        help="Naming mode that was passed to the export action")
    parser.add_argument(
        "--exported-count",
        type=int,
        default=EXPECTED_DIAGRAM_COUNT,
        help="How many files the action reported as exported")
    return parser.parse_args()


def list_files(output_dir: str) -> list[str]:
    """Returns the sorted list of regular file names directly inside
    ``output_dir``.
    """
    if not os.path.isdir(output_dir):
        fail(f"output directory {output_dir} does not exist")
    return sorted(
        name for name in os.listdir(output_dir)
        if os.path.isfile(os.path.join(output_dir, name))
    )


def assert_file_count(files: list[str], expected: int) -> None:
    """Fails if the number of files differs from ``expected``."""
    if len(files) != expected:
        fail(f"expected {expected} files, got {len(files)}: {files}")


def assert_extensions(files: list[str], expected_extension: str) -> None:
    """Fails if any filename doesn't end in ``.expected_extension``
    (case-insensitive).
    """
    wrong = [
        name for name in files
        if not name.lower().endswith(f".{expected_extension}")
    ]
    if wrong:
        fail(
            f"files don't have .{expected_extension} extension: {wrong}")


def assert_file_size(path: str) -> None:
    """Fails if ``path`` is smaller than {@link MINIMUM_FILE_SIZE}."""
    size = os.path.getsize(path)
    if size <= MINIMUM_FILE_SIZE:
        fail(
            f"{os.path.basename(path)} is suspiciously small "
            f"({size} bytes)")


def assert_magic_bytes(path: str, image_format: str) -> None:
    """Fails if the file at ``path`` doesn't start with one of the
    magic-byte prefixes registered for ``image_format``.
    """
    with open(path, "rb") as stream:
        header = stream.read(16)
    expected = FORMAT_MAGIC[image_format]
    if not any(header.startswith(prefix) for prefix in expected):
        fail(
            f"{os.path.basename(path)} doesn't start with a recognised "
            f"{image_format} magic-byte signature (got: {header[:8]!r})")


def assert_naming(stem: str, naming: str) -> None:
    """Fails if the filename ``stem`` doesn't match the regex for the
    given naming mode.
    """
    pattern = NAMING_PATTERN[naming]
    if not pattern.match(stem):
        fail(
            f"filename stem {stem!r} doesn't match {naming} pattern "
            f"{pattern.pattern}")


def verify(args: argparse.Namespace) -> None:
    """Runs every check on the configured directory."""
    files = list_files(args.output_dir)
    assert_file_count(files, args.exported_count)
    assert_extensions(files, FORMAT_EXTENSION[args.format])
    for name in files:
        path = os.path.join(args.output_dir, name)
        assert_file_size(path)
        assert_magic_bytes(path, args.format)
        assert_naming(os.path.splitext(name)[0], args.naming)
    print(
        f"All checks passed: {len(files)} files, "
        f"format={args.format}, naming={args.naming}")


if __name__ == "__main__":
    verify(parse_arguments())

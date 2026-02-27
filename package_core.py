#!/usr/bin/env python3
import argparse
import tarfile
from pathlib import Path
from datetime import datetime


EXCLUDE_DIRS = {
    ".git",
    ".venv",
    "venv",
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    "Pyverilog",
    "benchmark_results",
    "processed_data",
    "processed_signals",
}
EXCLUDE_SUBPATHS = {
    Path("benchmark/debugging/results"),
}
EXCLUDE_PYTHON_SUBPATHS = {
    Path("hdlsearch/database/systemverilog_database"),
    Path("hdlsearch/database/verilog_database"),
}

TESTSET_DIRS = [
    Path("hdlsearch/benchmark_generation/generated"),
    Path("benchmark/search"),
    Path("benchmark/debugging"),
]

TESTSET_EXTS = (
    ".json",
    ".jsonl",
    ".json.gz",
    ".jsonl.gz",
    ".tar.gz",
    ".txt",
)


def is_excluded(path: Path, root: Path) -> bool:
    try:
        rel = path.relative_to(root)
    except ValueError:
        return True
    if any(rel.is_relative_to(p) for p in EXCLUDE_SUBPATHS):
        return True
    return any(part in EXCLUDE_DIRS for part in rel.parts)


def iter_python_files(root: Path):
    for path in root.rglob("*.py"):
        if path.is_file() and not is_excluded(path, root):
            rel = path.relative_to(root)
            if any(rel.is_relative_to(p) for p in EXCLUDE_PYTHON_SUBPATHS):
                continue
            yield path


def iter_testset_files(root: Path):
    for base in TESTSET_DIRS:
        base_path = root / base
        if not base_path.exists():
            continue
        for path in base_path.rglob("*"):
            if not path.is_file():
                continue
            if is_excluded(path, root):
                continue
            if path.name.endswith(TESTSET_EXTS):
                yield path


def build_archive(root: Path, output_path: Path, dry_run: bool) -> int:
    files = set(iter_python_files(root))
    files.update(iter_testset_files(root))

    if dry_run:
        for path in sorted(files):
            print(path.relative_to(root))
        return len(files)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output_path, "w:gz") as tar:
        for path in sorted(files):
            tar.add(path, arcname=path.relative_to(root))
    return len(files)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Package core Python files and test sets into a tar.gz archive."
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Output tar.gz path (default: HDLxGraph_core_<timestamp>.tar.gz in repo root)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List files that would be included without creating the archive",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = root / output_path
    else:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        output_path = root / f"HDLxGraph_core_{timestamp}.tar.gz"

    count = build_archive(root, output_path, args.dry_run)
    if args.dry_run:
        print(f"\nFiles selected: {count}")
    else:
        print(f"Archive created: {output_path}")
        print(f"Files included: {count}")


if __name__ == "__main__":
    main()

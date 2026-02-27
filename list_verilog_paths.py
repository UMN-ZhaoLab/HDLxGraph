#!/usr/bin/env python3
from pathlib import Path
import argparse


def main() -> None:
    parser = argparse.ArgumentParser(
        description="List Verilog file paths under a root directory."
    )
    parser.add_argument(
        "--root",
        default="hdlsearch/database/verilog_database",
        help="Root directory to scan",
    )
    parser.add_argument(
        "--output",
        default="verilog_files.txt",
        help="Output txt file path",
    )
    parser.add_argument(
        "--absolute",
        action="store_true",
        help="Write absolute paths instead of relative",
    )
    parser.add_argument(
        "--ext",
        nargs="*",
        default=[".v", ".sv", ".vh", ".svh"],
        help="File extensions to include",
    )
    args = parser.parse_args()

    root = Path(args.root)
    if not root.exists() or not root.is_dir():
        raise SystemExit(f"Root directory not found: {root}")

    exts = {ext.lower() for ext in args.ext}
    files = []
    for path in root.rglob("*"):
        if path.is_file() and path.suffix.lower() in exts:
            files.append(path.resolve() if args.absolute else path)

    files_sorted = sorted(files, key=lambda p: str(p))
    output = Path(args.output)
    if output.parent and not output.parent.exists():
        output.parent.mkdir(parents=True, exist_ok=True)

    with output.open("w", encoding="utf-8") as handle:
        for path in files_sorted:
            handle.write(f"{path}\n")

    print(f"Wrote {len(files_sorted)} paths to {output}")


if __name__ == "__main__":
    main()

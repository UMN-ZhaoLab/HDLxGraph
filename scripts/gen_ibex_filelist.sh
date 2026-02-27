#!/usr/bin/env bash
set -euo pipefail

# Generate an HDL file list for the cloned ibex tree under test_files/ibex.
# Output: test_files/ibex/hdl_filelist.txt (paths relative to that file's directory).

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${REPO_ROOT}/test_files/ibex"
OUT_FILE="${SRC_DIR}/hdl_filelist.txt"

if [[ ! -d "${SRC_DIR}" ]]; then
  echo "ibex source directory not found at ${SRC_DIR}" >&2
  exit 1
fi

# Collect HDL extensions; skip common VCS/build artifacts and .git.
find "${SRC_DIR}" \
  -type f \
  \( -name "*.sv" -o -name "*.svh" -o -name "*.v" -o -name "*.vh" \) \
  ! -path "*/.git/*" \
  ! -path "*/build/*" \
  ! -path "*/out/*" \
  | sort \
  | sed "s|^${SRC_DIR}/|./|" > "${OUT_FILE}"

echo "Wrote $(wc -l < "${OUT_FILE}") entries to ${OUT_FILE}"

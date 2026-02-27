#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT_DIR}/dist"
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
OUTPUT_PATH="${1:-${OUT_DIR}/netlist2neo4j-core-${TIMESTAMP}.tar.gz}"

mkdir -p "$(dirname "$OUTPUT_PATH")"

core_paths=(
  "build.mill"
  ".mill-version"
  ".scalafmt.conf"
  "README.md"
  "SYSTEM_DESIGN.md"
  "config"
  "scripts"
  "netlist2neo4j/src"
)

include_args=()
for path in "${core_paths[@]}"; do
  if [ -e "${ROOT_DIR}/${path}" ]; then
    include_args+=("${path}")
  fi
done

if [ "${#include_args[@]}" -eq 0 ]; then
  echo "No core files found to package." >&2
  exit 1
fi

tar -czf "${OUTPUT_PATH}" \
  --exclude-vcs \
  --exclude='*/test/*' \
  --exclude='*/test_files/*' \
  --exclude='*/out/*' \
  --exclude='*/output/*' \
  --exclude='*/ast_test/*' \
  --exclude='*/batch_test/*' \
  --exclude='*.log' \
  --exclude='netlist.json' \
  -C "${ROOT_DIR}" \
  "${include_args[@]}"

echo "Created: ${OUTPUT_PATH}"

#!/usr/bin/env python3
"""
Clone HDLxGraph Verilog database repos from a fixed mapping into a fixed path.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path


# Fixed destination as requested.
TARGET_BASE = Path("hdlsearch/database/verilog_database")

# Fixed repo mapping by local directory name.
REPO_MAP: dict[str, str] = {
    "aib-phy-hardware": "https://github.com/chipsalliance/aib-phy-hardware.git",
    "cnn_hardware_acclerator_for_fpga": "https://github.com/sumanth-kalluri/cnn_hardware_acclerator_for_fpga.git",
    "e203_hbirdv2": "https://github.com/riscv-mcu/e203_hbirdv2.git",
    "mips-cpu": "https://github.com/jmahler/mips-cpu.git",
    "riffa": "https://github.com/KastnerRG/riffa.git",
    "verilog-axis": "https://github.com/alexforencich/verilog-axis.git",
    "verilog-ethernet": "https://github.com/alexforencich/verilog-ethernet.git",
    "verilog-pcie": "https://github.com/alexforencich/verilog-pcie.git",
    "Coffee-machine-control-circuit-based-on-FPGA-V3.2": "https://github.com/VeriMaverick/Coffee-machine-control-circuit-based-on-FPGA-V3.2",
    "FPGA_IIC_EEPROM": "https://github.com/IvanXiang/FPGA_IIC_EEPROM",
    "JPEG-image-compression-algorithm-based-on-FPGA": "https://github.com/VeriMaverick/JPEG-image-compression-algorithm-based-on-FPGA",
}

# Keep unresolved names visible in code for manual completion.
PENDING_REPOS: tuple[str, ...] = (
    "Coffee-machine-control-circuit-based-on-FPGA-V3.2",
    "FPGA_IIC_EEPROM",
    "JPEG-image-compression-algorithm-based-on-FPGA",
)


def run(cmd: list[str], cwd: Path | None = None) -> None:
    result = subprocess.run(cmd, cwd=str(cwd) if cwd else None, text=True)
    if result.returncode != 0:
        raise SystemExit(f"Command failed: {' '.join(cmd)}")


def main() -> None:
    if shutil.which("git") is None:
        raise SystemExit("git is required but not found in PATH")

    target = TARGET_BASE.resolve()
    target.mkdir(parents=True, exist_ok=True)

    for local_name, repo_url in REPO_MAP.items():
        dst = target / local_name
        if dst.exists():
            print(f"Removing existing directory: {dst}")
            shutil.rmtree(dst)

        print(f"Cloning {repo_url} -> {dst}")
        run(["git", "clone", "--depth", "1", repo_url, str(dst)])

    if PENDING_REPOS:
        print("\nPending repo mapping (manual update needed):")
        for name in PENDING_REPOS:
            print(f"- {name}")

    print("\nDone.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(130)

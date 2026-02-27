#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path


IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
MODULE_RE = re.compile(r"^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)")
ASSIGN_RE = re.compile(r"^\s*assign\s+(.+?)\s*=\s*(.+?)\s*;\s*$")
PROC_ASSIGN_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_\\[\\]\\.]+)\s*(<=|=)\s*(.+?)\s*;\s*$")

KEYWORDS = {
    "module",
    "endmodule",
    "assign",
    "always",
    "begin",
    "end",
    "if",
    "else",
    "case",
    "endcase",
    "for",
    "while",
    "wire",
    "reg",
    "logic",
    "input",
    "output",
    "inout",
    "parameter",
    "localparam",
    "generate",
    "endgenerate",
    "posedge",
    "negedge",
}


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//.*", "", text)
    return text


def normalize_name(token: str) -> str:
    # part-select and bit-select removal, keep hierarchical-style tokens.
    token = token.strip()
    token = token.split("[", 1)[0]
    return token


def parse_module_name(lines):
    for line in lines:
        m = MODULE_RE.match(line)
        if m:
            return m.group(1)
    return "unknown_module"


def collect_identifiers(expr: str):
    ids = []
    for token in IDENT_RE.findall(expr):
        if token.lower() in KEYWORDS:
            continue
        if token.isdigit():
            continue
        ids.append(normalize_name(token))
    return ids


def build_dfg(verilog_path: Path, max_nodes: int):
    raw = verilog_path.read_text(encoding="utf-8", errors="ignore")
    stripped = strip_comments(raw)
    lines = stripped.splitlines()
    module_name = parse_module_name(lines)

    nodes = {}
    edges = []

    def ensure_signal_node(name: str, line_no: int):
        if not name:
            return None
        node_id = f"{module_name}::sig::{name}"
        if node_id not in nodes:
            if len(nodes) >= max_nodes:
                return None
            nodes[node_id] = {
                "id": node_id,
                "name": name,
                "nodeKind": "signal",
                "module": module_name,
                "line": line_no,
                "operator": None,
            }
        return node_id

    for idx, line in enumerate(lines, start=1):
        lhs = None
        rhs = None
        edge_type = "assign"

        m = ASSIGN_RE.match(line)
        if m:
            lhs = normalize_name(m.group(1))
            rhs = m.group(2)
            edge_type = "assign"
        else:
            m = PROC_ASSIGN_RE.match(line)
            if m:
                lhs = normalize_name(m.group(1))
                rhs = m.group(3)
                edge_type = "procedural"

        if not lhs or rhs is None:
            continue

        dst_id = ensure_signal_node(lhs, idx)
        if not dst_id:
            continue

        src_tokens = [tok for tok in collect_identifiers(rhs) if tok != lhs]
        for src in src_tokens:
            src_id = ensure_signal_node(src, idx)
            if not src_id:
                continue
            edges.append(
                {
                    "src": src_id,
                    "dst": dst_id,
                    "edgeType": edge_type,
                    "line": idx,
                }
            )

    root_signals = sorted({n["name"] for n in nodes.values()})
    return {
        "nodes": list(nodes.values()),
        "edges": edges,
        "rootSignals": root_signals,
    }


def main():
    parser = argparse.ArgumentParser(description="Extract lightweight DFG from Verilog")
    parser.add_argument("--verilog-file", required=True)
    parser.add_argument("--output-file", required=True)
    parser.add_argument("--max-nodes", type=int, default=5000)
    parser.add_argument("--include-temp-nodes", default="true")
    args = parser.parse_args()

    verilog_path = Path(args.verilog_file)
    graph = build_dfg(verilog_path, max(1, args.max_nodes))
    out = Path(args.output_file)
    out.write_text(json.dumps(graph, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()


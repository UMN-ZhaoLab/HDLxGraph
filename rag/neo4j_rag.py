from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set

from py2neo import Graph
from pyverilog.vparser.parser import ParseError
from transformers import AutoModel, AutoTokenizer
import torch
import torch.nn.functional as F

from rag.prompting import SearchQuery


class Neo4jRAG:
    def __init__(
        self,
        generate_func,
        uri: str = "bolt://localhost:7687",
        user: str = "neo4j",
        password: str = "neo4j123",
        checkpoint: str = "Salesforce/codet5p-110m-embedding",
        device: Optional[str] = None,
    ):
        self.uri = uri
        self.user = user
        self.password = password
        self.graph: Optional[Graph] = None
        self.checkpoint = checkpoint
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.tokenizer = None
        self.model = None
        self.generate_func = generate_func

        self.connect()

    def connect(self):
        try:
            self.graph = Graph(self.uri, auth=(self.user, self.password))
            print("Successfully connected to Neo4j database")
        except Exception as e:
            print(f"Failed to connect to Neo4j: {str(e)}")
            self.graph = None

    def _load_embedding_model(self):
        if self.tokenizer is None:
            self.tokenizer = AutoTokenizer.from_pretrained(self.checkpoint, trust_remote_code=True)
        if self.model is None:
            self.model = AutoModel.from_pretrained(self.checkpoint, trust_remote_code=True).to(self.device)
            self.model.eval()

    def _encode_embedding(self, text: str) -> List[float]:
        self._load_embedding_model()
        encoded = self.tokenizer(
            text,
            return_tensors="pt",
            truncation=True,
            max_length=512,
            padding=True,
        )
        encoded = {k: v.to(self.device) for k, v in encoded.items()}
        with torch.no_grad():
            outputs = self.model(**encoded)
        hidden = outputs.last_hidden_state if hasattr(outputs, "last_hidden_state") else outputs[0]
        mask = encoded.get("attention_mask")
        if mask is not None:
            mask = mask.unsqueeze(-1).to(hidden.dtype)
            pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1.0)
        else:
            pooled = hidden.mean(dim=1)
        normalized = F.normalize(pooled, p=2, dim=1)
        return normalized[0].detach().cpu().tolist()

    def _has_label(self, label: str) -> bool:
        if not self.graph:
            return False
        query = f"MATCH (n:{label}) RETURN count(n) AS cnt"
        try:
            cnt = self.graph.run(query).evaluate() or 0
            return int(cnt) > 0
        except Exception:
            return False

    def build_database_from_scala(self, verilog_paths: Iterable[str], config_path: Optional[str] = None):
        paths = [str(Path(p).resolve()) for p in verilog_paths]
        if not paths:
            print("No Verilog paths provided; skip Scala import")
            return

        repo_root = Path(__file__).resolve().parents[1]
        cfg = Path(config_path) if config_path else repo_root / "config" / "config.json"
        if not cfg.is_absolute():
            cfg = repo_root / cfg

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as fp:
            for path in paths:
                fp.write(path + "\n")
            list_file = fp.name

        cmd = ["mill", "--no-server", "netlist2neo4j.run", "--config", str(cfg), list_file]
        print(f"Running Scala importer on {len(paths)} files...")
        try:
            result = subprocess.run(
                cmd,
                cwd=str(repo_root),
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            if result.returncode != 0:
                stderr_tail = "\n".join(result.stderr.splitlines()[-40:])
                raise RuntimeError(
                    "Scala import failed with exit code "
                    f"{result.returncode}. Last stderr lines:\n{stderr_tail}"
                )
        finally:
            try:
                os.remove(list_file)
            except OSError:
                pass

    # Legacy entrypoint kept for compatibility only.
    def store_verilog(self, file_path):
        print("Warning: store_verilog is legacy; prefer build_database_from_scala().")
        from .test_neo4j import store_verilog_graph

        iterable = file_path.keys() if isinstance(file_path, dict) else file_path
        for document in iterable:
            try:
                store_verilog_graph(document)
            except ParseError:
                continue
            except Exception as e:
                print(f"Error processing file: {str(e)}")
                print("skip this file...")
                continue

    def _parse_ast_query(self, model: str, description: str) -> SearchQuery:
        system_message = (
            "You are an HDL query parser. Split the query into three keys:\n"
            "module_desc, block_desc, signal_name.\n"
            "Rules:\n"
            "- Return JSON only.\n"
            "- signal_name must be a concrete signal identifier when present.\n"
            "- If unknown, set empty string.\n"
            f"{SearchQuery.format_instructions()}"
        )

        response = self.generate_func(model, system_message, description)
        parsed = SearchQuery.parse(response)
        if parsed.is_empty():
            return SearchQuery(module_desc=description, block_desc=description, signal_name="")

        if not parsed.module_desc:
            parsed.module_desc = description
        if not parsed.block_desc:
            parsed.block_desc = description
        return parsed

    def _read_source_snippet(self, file_path: str, start_line: int, end_line: int, max_lines: int = 40) -> str:
        if not file_path:
            return ""
        path = Path(file_path)
        if not path.exists() or not path.is_file():
            return ""
        try:
            lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        except Exception:
            return ""

        start = max(1, int(start_line) if start_line else 1)
        end = int(end_line) if end_line else start
        if end < start:
            end = start
        if end - start + 1 > max_lines:
            end = start + max_lines - 1

        start_idx = min(start - 1, len(lines))
        end_idx = min(end, len(lines))
        snippet = "\n".join(lines[start_idx:end_idx]).strip()
        return snippet

    def _legacy_search_ast(self, description: str, top_k: int = 10, return_type: str = "text"):
        if not self.graph:
            return [] if return_type in {"list", "raw"} else "Did not find match result"

        query_embedding = self._encode_embedding(description)
        query = """
        MATCH (b:Block)
        WHERE b.code_embedding IS NOT NULL
        WITH b, gds.similarity.cosine(b.code_embedding, $query_embedding) AS similarity
        RETURN '' AS ast_module_id,
               b.id AS ast_block_id,
               '' AS module_name,
               b.type AS block_name,
               0.0 AS module_sim,
               similarity AS block_sim,
               similarity AS pair_score,
               false AS matched_signal,
               b.code AS code_snippet,
               '' AS file,
               0 AS start_line,
               0 AS end_line
        ORDER BY pair_score DESC
        LIMIT $top_k
        """
        rows = list(self.graph.run(query, query_embedding=query_embedding, top_k=top_k).data())
        if return_type == "raw":
            return rows
        if return_type == "list":
            return [r.get("code_snippet", "") for r in rows if r.get("code_snippet")]
        if not rows:
            return "Did not find match result"
        out = []
        for idx, row in enumerate(rows, 1):
            out.append(f"Result {idx} (pair_score={float(row.get('pair_score', 0.0)):.3f})")
            out.append(row.get("code_snippet", ""))
            out.append("-" * 50)
        return "\n".join(out)

    def search_ast(
        self,
        model: str,
        description: str,
        top_k: int = 10,
        top_m: int = 30,
        top_b: int = 200,
        return_type: str = "text",
        hard_signal_filter: bool = False,
        signal_bonus: float = 0.05,
    ):
        if not self.graph:
            return [] if return_type in {"list", "raw"} else "Did not find match result"

        has_ast = self._has_label("ASTModule") and self._has_label("ASTBlock")
        if not has_ast:
            print("Warning: AST schema not found; fallback to legacy Block retrieval")
            return self._legacy_search_ast(description, top_k=top_k, return_type=return_type)

        parsed = self._parse_ast_query(model, description)
        module_embedding = self._encode_embedding(parsed.module_desc)
        block_embedding = self._encode_embedding(parsed.block_desc)
        signal_name = parsed.signal_name.strip()

        cypher = """
        MATCH (m:ASTModule)
        WHERE m.embedding IS NOT NULL
        WITH m, gds.similarity.cosine(m.embedding, $module_embedding) AS module_sim
        ORDER BY module_sim DESC
        LIMIT $top_m
        WITH collect({id: m.id, module_sim: module_sim}) AS top_modules

        MATCH (b:ASTBlock)
        WHERE b.embedding IS NOT NULL
        WITH top_modules, b, gds.similarity.cosine(b.embedding, $block_embedding) AS block_sim
        ORDER BY block_sim DESC
        LIMIT $top_b

        UNWIND top_modules AS mdata
        MATCH (m:ASTModule {id: mdata.id})-[rel:CONTAIN]->(b)
        WHERE rel.type = 'has_block'
        OPTIONAL MATCH (b)-[:CONTAIN {type:'uses_signal'}]->(s:ASTSignal)
        WHERE $signal_name <> '' AND s.name = $signal_name
        WITH m, b, mdata.module_sim AS module_sim, block_sim, count(s) AS signal_hits
        WHERE ($hard_signal_filter = false OR $signal_name = '' OR signal_hits > 0)
        WITH m, b, module_sim, block_sim, (signal_hits > 0) AS matched_signal,
             CASE WHEN $signal_name <> '' AND signal_hits > 0 THEN $signal_bonus ELSE 0.0 END AS bonus
        WITH m, b, module_sim, block_sim, matched_signal,
             CASE
               WHEN ((module_sim + block_sim) / 2.0 + bonus) > 1.0 THEN 1.0
               ELSE ((module_sim + block_sim) / 2.0 + bonus)
             END AS pair_score
        RETURN m.id AS ast_module_id,
               b.id AS ast_block_id,
               m.name AS module_name,
               b.name AS block_name,
               module_sim,
               block_sim,
               pair_score,
               matched_signal,
               coalesce(b.source_file, b.file, '') AS file,
               toInteger(coalesce(b.start_line, b.line, 0)) AS start_line,
               toInteger(coalesce(b.end_line, b.line, 0)) AS end_line
        ORDER BY pair_score DESC
        LIMIT $top_k
        """

        rows = list(
            self.graph.run(
                cypher,
                module_embedding=module_embedding,
                block_embedding=block_embedding,
                signal_name=signal_name,
                hard_signal_filter=bool(hard_signal_filter),
                signal_bonus=float(signal_bonus),
                top_m=int(top_m),
                top_b=int(top_b),
                top_k=int(top_k),
            ).data()
        )

        for row in rows:
            row["code_snippet"] = self._read_source_snippet(
                row.get("file", ""),
                row.get("start_line", 0),
                row.get("end_line", 0),
            )

        if return_type == "raw":
            return {
                "parsed_query": {
                    "module_desc": parsed.module_desc,
                    "block_desc": parsed.block_desc,
                    "signal_name": parsed.signal_name,
                },
                "results": rows,
            }
        if return_type == "list":
            return [row.get("code_snippet", "") for row in rows if row.get("code_snippet")]

        if not rows:
            return "Did not find match result"

        output = [
            "Parsed query:",
            f"  module_desc: {parsed.module_desc}",
            f"  block_desc: {parsed.block_desc}",
            f"  signal_name: {parsed.signal_name}",
        ]
        for idx, row in enumerate(rows, 1):
            output.append(
                f"\nResult {idx} (pair_score={float(row.get('pair_score', 0.0)):.3f}, "
                f"module_sim={float(row.get('module_sim', 0.0)):.3f}, "
                f"block_sim={float(row.get('block_sim', 0.0)):.3f}, "
                f"matched_signal={bool(row.get('matched_signal', False))})"
            )
            output.append(
                f"Module={row.get('module_name', '')}, Block={row.get('block_name', '')}, "
                f"Range={row.get('file', '')}:{row.get('start_line', 0)}-{row.get('end_line', 0)}"
            )
            snippet = row.get("code_snippet", "")
            if snippet:
                output.append(snippet)
            output.append("-" * 50)
        return "\n".join(output)

    def _get_upstream_nodes(self, node_id: str, max_branch_per_node: int) -> List[Dict[str, Any]]:
        if not self.graph:
            return []
        query = """
        MATCH (src:DFGNode)-[r:DFG_FLOW]->(dst:DFGNode {id: $node_id})
        RETURN src.id AS id,
               coalesce(src.name, '') AS name,
               coalesce(src.nodeKind, '') AS node_kind,
               coalesce(src.module, '') AS module,
               toInteger(coalesce(src.line, 0)) AS line,
               coalesce(r.type, '') AS edge_type
        ORDER BY src.name
        LIMIT $max_branch
        """
        return list(
            self.graph.run(
                query,
                node_id=node_id,
                max_branch=int(max_branch_per_node),
            ).data()
        )

    def _get_anchor(self, node_id: str) -> Dict[str, Any]:
        if not self.graph:
            return {}
        query = """
        MATCH (d:DFGNode {id: $node_id})
        OPTIONAL MATCH (d)-[:ANCHOR_BLOCK]->(b:ASTBlock)
        WITH d, collect(DISTINCT b)[0] AS direct_block
        OPTIONAL MATCH (d)-[:REFERS_AST_SIGNAL]->(sig:ASTSignal)
        WITH d, direct_block, collect(DISTINCT sig) AS signals
        OPTIONAL MATCH (sig2:ASTSignal)<-[:CONTAIN {type:'uses_signal'}]-(ub:ASTBlock)
        WHERE sig2 IN signals
        WITH direct_block, collect(DISTINCT ub)[0] AS used_block
        RETURN coalesce(direct_block.module, used_block.module, '') AS module,
               coalesce(direct_block.name, used_block.name, '') AS block,
               coalesce(direct_block.source_file, used_block.source_file, direct_block.file, used_block.file, '') AS file,
               toInteger(coalesce(direct_block.start_line, used_block.start_line, direct_block.line, used_block.line, 0)) AS start_line,
               toInteger(coalesce(direct_block.end_line, used_block.end_line, direct_block.line, used_block.line, 0)) AS end_line
        """
        row = self.graph.run(query, node_id=node_id).data()
        return row[0] if row else {}

    def _build_upstream_tree(
        self,
        root: Dict[str, Any],
        max_depth: int,
        max_nodes: int,
        max_branch_per_node: int,
    ) -> Dict[str, Any]:
        anchors: Dict[str, Dict[str, Any]] = {}
        upstream_cache: Dict[str, List[Dict[str, Any]]] = {}
        seen_nodes: Set[str] = set()

        def expand(node: Dict[str, Any], depth: int, path: Set[str]) -> Dict[str, Any]:
            node_id = node.get("id", "")
            current = dict(node)
            current.setdefault("children", [])
            current.setdefault("cycle", False)

            if node_id and node_id not in anchors:
                anchors[node_id] = self._get_anchor(node_id)
            current["anchor"] = anchors.get(node_id, {})

            if depth >= max_depth:
                return current
            if len(seen_nodes) >= max_nodes:
                return current
            if node_id:
                seen_nodes.add(node_id)

            if node_id not in upstream_cache:
                upstream_cache[node_id] = self._get_upstream_nodes(node_id, max_branch_per_node)

            children = []
            for src in upstream_cache[node_id]:
                src_id = src.get("id", "")
                child = {
                    "id": src_id,
                    "name": src.get("name", ""),
                    "node_kind": src.get("node_kind", ""),
                }
                if src_id in path:
                    child["cycle"] = True
                    child["children"] = []
                    child["anchor"] = self._get_anchor(src_id)
                    children.append(child)
                    continue
                if len(seen_nodes) >= max_nodes:
                    break
                children.append(expand(child, depth + 1, path | {src_id}))

            current["children"] = children
            return current

        return expand(root, 0, {root.get("id", "")})

    def _format_tree_node(self, node: Dict[str, Any]) -> str:
        name = node.get("name") or node.get("id") or "unknown"
        node_kind = node.get("node_kind") or "node"
        anchor = node.get("anchor", {})
        location = ""
        file = anchor.get("file", "")
        start_line = int(anchor.get("start_line", 0) or 0)
        end_line = int(anchor.get("end_line", 0) or 0)
        module = anchor.get("module", "")
        block = anchor.get("block", "")
        if file:
            location = f"{file}:{start_line}-{end_line}"
        meta_parts = [
            f"kind={node_kind}",
            f"module={module}" if module else "",
            f"block={block}" if block else "",
            f"loc={location}" if location else "",
        ]
        meta = ", ".join(part for part in meta_parts if part)
        cycle = " (cycle)" if node.get("cycle", False) else ""
        return f"{name} [{meta}]{cycle}" if meta else f"{name}{cycle}"

    def _render_tree_lines(self, node: Dict[str, Any], prefix: str = "", is_last: bool = True) -> List[str]:
        connector = "└─ " if is_last else "├─ "
        lines = [prefix + connector + self._format_tree_node(node)]
        children = node.get("children", [])
        child_prefix = prefix + ("   " if is_last else "│  ")
        for idx, child in enumerate(children):
            lines.extend(self._render_tree_lines(child, child_prefix, idx == len(children) - 1))
        return lines

    def _legacy_search_dfg(self, signal_name: str, return_type: str = "text_tree"):
        if not self.graph:
            return [] if return_type == "raw" else "DFG schema not available"
        query = """
        MATCH (s:Signal {name: $signal_name})
        OPTIONAL MATCH path=(src:Signal)-[:FLOWS_TO*1..3]->(s)
        RETURN s.name AS signal_name, count(path) AS path_count
        """
        row = self.graph.run(query, signal_name=signal_name).evaluate()
        msg = f"Legacy DFG fallback executed for signal={signal_name}."
        if row is None:
            msg += " No result found."
        if return_type == "raw":
            return {"signal_name": signal_name, "legacy": True}
        return msg

    def search_dfg(
        self,
        signal_name: str,
        max_depth: int = 4,
        max_nodes: int = 300,
        max_branch_per_node: int = 8,
        return_type: str = "text_tree",
        case_insensitive: bool = False,
    ):
        if not self.graph:
            return [] if return_type == "raw" else "DFG retrieval unavailable: no Neo4j connection"

        has_dfg = self._has_label("DFGNode") and self._has_label("ASTSignal")
        if not has_dfg:
            print("Warning: DFG/AST schema not found; fallback to legacy signal flow")
            return self._legacy_search_dfg(signal_name, return_type=return_type)

        roots_query = """
        MATCH (s:ASTSignal)
        WHERE ($case_insensitive = true AND toLower(s.name) = toLower($signal_name))
           OR ($case_insensitive = false AND s.name = $signal_name)
        OPTIONAL MATCH (d1:DFGNode)-[:REFERS_AST_SIGNAL]->(s)
        OPTIONAL MATCH (s)-[:REFERS_AST_SIGNAL]->(d2:DFGNode)
        WITH s, [x IN collect(DISTINCT d1) + collect(DISTINCT d2) WHERE x IS NOT NULL] AS roots
        UNWIND roots AS root
        RETURN s.module AS module,
               s.name AS signal_name,
               s.id AS ast_signal_id,
               root.id AS root_id,
               coalesce(root.name, '') AS root_name,
               coalesce(root.nodeKind, '') AS root_node_kind
        """

        root_rows = list(
            self.graph.run(
                roots_query,
                signal_name=signal_name,
                case_insensitive=bool(case_insensitive),
            ).data()
        )
        if not root_rows:
            msg = f"Did not find DFG roots for signal '{signal_name}'"
            return {"signal_name": signal_name, "trees": []} if return_type == "raw" else msg

        grouped: Dict[str, List[Dict[str, Any]]] = {}
        for row in root_rows:
            module = row.get("module") or "<unknown_module>"
            grouped.setdefault(module, []).append(row)

        trees = []
        for module, rows in grouped.items():
            dedup = {}
            for row in rows:
                dedup[row.get("root_id", "")] = row
            for row in dedup.values():
                root = {
                    "id": row.get("root_id", ""),
                    "name": row.get("root_name") or row.get("signal_name", ""),
                    "node_kind": row.get("root_node_kind", ""),
                }
                tree = self._build_upstream_tree(
                    root,
                    max_depth=max_depth,
                    max_nodes=max_nodes,
                    max_branch_per_node=max_branch_per_node,
                )
                trees.append(
                    {
                        "module": module,
                        "target_signal": row.get("signal_name", signal_name),
                        "ast_signal_id": row.get("ast_signal_id", ""),
                        "root": tree,
                    }
                )

        if return_type == "raw":
            return {"signal_name": signal_name, "trees": trees}

        if return_type == "list":
            return [tree["target_signal"] for tree in trees]

        output = []
        for tree in trees:
            output.append(f"{tree['target_signal']} [module={tree['module']}]")
            output.extend(self._render_tree_lines(tree["root"], prefix="", is_last=True))
            output.append("-" * 50)
        return "\n".join(output)

    def search(
        self,
        model: str,
        description: str,
        top_k: int = 10,
        return_type: str = "text",
        mode: str = "ast",
        **kwargs,
    ):
        selected_mode = (mode or "ast").lower()
        if selected_mode == "dfg":
            return self.search_dfg(
                signal_name=description,
                return_type=return_type,
                max_depth=int(kwargs.get("max_depth", 4)),
                max_nodes=int(kwargs.get("max_nodes", 300)),
                max_branch_per_node=int(kwargs.get("max_branch_per_node", 8)),
                case_insensitive=bool(kwargs.get("case_insensitive", False)),
            )

        return self.search_ast(
            model=model,
            description=description,
            top_k=top_k,
            top_m=int(kwargs.get("top_m", 30)),
            top_b=int(kwargs.get("top_b", 200)),
            return_type=return_type,
            hard_signal_filter=bool(kwargs.get("hard_signal_filter", False)),
            signal_bonus=float(kwargs.get("signal_bonus", 0.05)),
        )

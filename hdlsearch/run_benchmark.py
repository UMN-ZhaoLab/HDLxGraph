#!/usr/bin/env python3
"""
Code Location Benchmark Runner
支持 embedding 和 BM25 两种检索方式
"""

import argparse
import csv
import enum
import json
import os
import re
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np
import torch
import torch.nn.functional as F
from rank_bm25 import BM25Okapi
from rouge import Rouge
from sklearn.metrics.pairwise import cosine_similarity
from transformers import AutoModel, AutoTokenizer


class CodeDatabase:
    """代码数据库类，用于存储和检索代码片段"""

    def __init__(self, database_path: str, chunk_size: int = 1000, overlap: int = 200):
        self.database_path = Path(database_path)
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.code_snippets = []
        self.snippet_info = []

    def load_database(self):
        """加载数据库中的所有代码文件"""
        print("Loading code database...")

        # 支持的代码文件扩展名
        code_extensions = {".v", ".sv", ".vh", ".svh", ".vhd", ".vhdl"}

        for root, dirs, files in os.walk(self.database_path):
            for file in files:
                if Path(file).suffix.lower() in code_extensions:
                    file_path = Path(root) / file
                    try:
                        with open(
                            file_path, "r", encoding="utf-8", errors="ignore"
                        ) as f:
                            content = f.read()

                        # 按固定长度分割代码
                        code_blocks = self._split_code_blocks(
                            content, self.chunk_size, self.overlap
                        )

                        for i, block in enumerate(code_blocks):
                            if len(block.strip()) > 50:  # 过滤太短的代码块
                                self.code_snippets.append(block)
                                self.snippet_info.append(
                                    {
                                        "file": str(
                                            file_path.relative_to(self.database_path)
                                        ),
                                        "block_id": i,
                                        "content": block,
                                    }
                                )
                    except Exception as e:
                        print(f"Warning: Could not read {file_path}: {e}")

        print(f"Loaded {len(self.code_snippets)} code snippets from database")

    def _split_code_blocks(
        self, content: str, chunk_size: int = 1000, overlap: int = 200
    ) -> List[str]:
        """将代码内容按固定长度分割成chunks"""
        # 预处理内容：移除多余空白，保留基本结构
        content = re.sub(r"\s+", " ", content.strip())

        # 如果内容长度小于chunk_size，直接返回
        if len(content) <= chunk_size:
            return [content] if content else []

        chunks = []
        start = 0

        while start < len(content):
            end = start + chunk_size

            # 如果不是最后一个chunk，尝试在合适的位置分割
            if end < len(content):
                # 寻找合适的分割点（空格、分号、括号等）
                split_chars = [" ", ";", ",", ")", "}", "]"]
                best_split = end

                for char in split_chars:
                    # 在chunk_size范围内寻找最后一个分割字符
                    pos = content.rfind(char, start, end)
                    if pos > start and pos < end:
                        best_split = pos + 1
                        break

                chunk = content[start:best_split]
                start = best_split - overlap  # 添加重叠
            else:
                # 最后一个chunk
                chunk = content[start:]
                start = len(content)

            if chunk.strip():
                chunks.append(chunk.strip())

        return chunks


class BM25Retriever:
    """BM25检索器"""

    TOKEN_ALIASES = {
        "clk": ["clock"],
        "clock": ["clk"],
        "rst": ["reset"],
        "reset": ["rst"],
        "wr": ["write"],
        "write": ["wr"],
        "rd": ["read"],
        "read": ["rd"],
        "addr": ["address"],
        "address": ["addr"],
        "cfg": ["config"],
        "config": ["cfg"],
        "sel": ["select"],
        "select": ["sel"],
        "en": ["enable"],
        "enable": ["en"],
        "cnt": ["count"],
        "count": ["cnt"],
        "ptr": ["pointer"],
        "pointer": ["ptr"],
        "rx": ["receive"],
        "receive": ["rx"],
        "tx": ["transmit"],
        "transmit": ["tx"],
    }
    SHORT_ALIAS_TOKENS = {"rd", "wr", "rx", "tx", "en"}

    def __init__(self, code_db: CodeDatabase):
        self.code_db = code_db
        self.bm25 = None
        self.corpus_tokens = []

    def build_index(self):
        """构建BM25索引"""
        print("Building BM25 index...")

        # 预处理代码片段，提取关键信息
        processed_snippets = []
        for snippet in self.code_db.code_snippets:
            # 移除注释和多余空白
            processed = self._preprocess_code(snippet)
            processed_snippets.append(processed)

        self.corpus_tokens = [self._tokenize(text) for text in processed_snippets]
        self.bm25 = BM25Okapi(self.corpus_tokens) if self.corpus_tokens else None
        print(f"BM25 index built with {len(self.corpus_tokens)} documents")

    def _preprocess_code(self, code: str) -> str:
        """预处理代码文本"""
        # 移除注释
        code = re.sub(r"//.*$", "", code, flags=re.MULTILINE)
        code = re.sub(r"/\*.*?\*/", "", code, flags=re.DOTALL)

        # 移除多余空白
        code = re.sub(r"\s+", " ", code)

        # 保留关键字和标识符
        return code.strip()

    def _split_identifier(self, identifier: str) -> List[str]:
        """保留完整标识符，并做有限子词拆分"""
        identifier = identifier.strip()
        if not identifier:
            return []
        lower = identifier.lower()
        parts = [lower]
        underscore_parts = [p for p in lower.split("_") if len(p) >= 4]
        if underscore_parts:
            sorted_parts = sorted(underscore_parts, key=len, reverse=True)
            top_parts = sorted_parts[:2]
            if len(sorted_parts) > 2 and len(sorted_parts[2]) >= 5:
                top_parts.append(sorted_parts[2])
            for part in top_parts:
                if part not in parts:
                    parts.append(part)

        return list(dict.fromkeys(parts))

    def _tokenize(self, text: str, expand_aliases: bool = False) -> List[str]:
        """简易tokenizer，提取标识符并做轻量扩展"""
        identifiers = re.findall(r"[A-Za-z_][A-Za-z0-9_]+", text)
        tokens = []
        for identifier in identifiers:
            tokens.extend(self._split_identifier(identifier))

        tokens = [token for token in tokens if len(token) > 1]

        if expand_aliases:
            expanded = []
            for token in tokens:
                expanded.append(token)
                if len(token) >= 3 or token in self.SHORT_ALIAS_TOKENS:
                    expanded.extend(self.TOKEN_ALIASES.get(token, []))
            tokens = expanded

        return tokens

    def search(self, query: str, top_k: int = 5) -> List[Tuple[int, float]]:
        """搜索最相关的代码片段"""
        if not self.bm25:
            raise ValueError("Index not built. Call build_index() first.")

        # 预处理查询
        processed_query = self._preprocess_code(query)

        # 计算BM25分数
        query_tokens = self._tokenize(processed_query, expand_aliases=False)
        scores = self.bm25.get_scores(query_tokens)

        # 获取top-k结果
        top_indices = np.argsort(scores)[::-1][:top_k]

        results = []
        for idx in top_indices:
            if scores[idx] > 0:
                results.append((idx, float(scores[idx])))

        return results


class EmbeddingRetriever:
    """Embedding检索器"""

    def __init__(self, code_db: CodeDatabase, device: str = "cuda"):
        self.code_db = code_db
        self.device = device

        # 如果没有指定模型，使用推荐的模型
        self.model_name = "Salesforce/codet5p-110m-embedding"
        print(f"Using default model: {self.model_name}")

        self.tokenizer = None
        self.model = None
        self.embeddings = None

    def build_index(self):
        """构建embedding索引"""
        print(f"Building embedding index with model: {self.model_name}")

        # 加载 tokenizer 和 model
        self.tokenizer = AutoTokenizer.from_pretrained(
            self.model_name, trust_remote_code=True
        )
        self.model = AutoModel.from_pretrained(
            self.model_name, trust_remote_code=True
        ).to(self.device)
        self.model.eval()

        # 预处理代码片段
        processed_snippets = []
        for snippet in self.code_db.code_snippets:
            processed = self._preprocess_code(snippet)
            processed_snippets.append(processed)

        # 计算embeddings
        print("Computing embeddings (this may take a while for CodeT5+)...")
        embeddings = []
        with torch.no_grad():
            for code in processed_snippets:
                inputs = self.tokenizer(
                    code,
                    return_tensors="pt",
                    truncation=True,
                    max_length=512,
                    padding=False,
                ).to(self.device)
                outputs = self.model(**inputs, return_dict=True)
                hidden = self._extract_hidden(outputs)
                embedding = self._pool_mean(hidden, inputs.get("attention_mask"))
                embeddings.append(embedding.cpu())

        if embeddings:
            self.embeddings = torch.stack(embeddings).to(torch.float32)
        else:
            self.embeddings = torch.empty((0, 1), dtype=torch.float32)
        print(f"Embedding index built with shape: {self.embeddings.shape}")

    def _preprocess_code(self, code: str) -> str:
        """预处理代码文本"""
        return code.strip()

    def search(self, query: str, top_k: int = 5) -> List[Tuple[int, float]]:
        """搜索最相关的代码片段"""
        if self.model is None or self.embeddings is None:
            raise ValueError("Index not built. Call build_index() first.")

        # 预处理查询
        processed_query = self._preprocess_code(query)

        # 计算查询的embedding
        inputs = self.tokenizer(
            processed_query,
            return_tensors="pt",
            truncation=True,
            max_length=512,
            padding=False,
        ).to(self.device)
        with torch.no_grad():
            output = self.model(**inputs, return_dict=True)
            hidden = self._extract_hidden(output)
            query_embedding = self._pool_mean(hidden, inputs.get("attention_mask")).cpu().unsqueeze(0)

        # 计算余弦相似度
        similarities = cosine_similarity(
            query_embedding, self.embeddings
        ).flatten()
        # 获取top-k结果
        top_indices = np.argsort(similarities)[::-1][:top_k]
        results = []
        for idx in top_indices:
            if similarities[idx] > 0:
                results.append((idx, float(similarities[idx])))
        return results

    @staticmethod
    def _extract_hidden(outputs: Any) -> torch.Tensor:
        if hasattr(outputs, "last_hidden_state"):
            return outputs.last_hidden_state
        if torch.is_tensor(outputs):
            return outputs
        if isinstance(outputs, (list, tuple)) and outputs:
            return outputs[0]
        raise ValueError("Unsupported model output format for embeddings")

    @staticmethod
    def _pool_mean(hidden: torch.Tensor, attention_mask: torch.Tensor | None) -> torch.Tensor:
        # attention-mask-aware mean pooling with L2 normalization
        if hidden.dim() == 3:  # [batch, seq, dim]
            if attention_mask is not None:
                mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
                masked = hidden * mask
                denom = mask.sum(dim=1).clamp(min=1.0)
                pooled = (masked.sum(dim=1) / denom).squeeze(0)
            else:
                pooled = hidden.mean(dim=1).squeeze(0)
        elif hidden.dim() == 2:  # [batch, dim] or [seq, dim]
            if attention_mask is not None and attention_mask.dim() >= 1:
                mask = attention_mask.view(-1).to(hidden.dtype)
                denom = mask.sum().clamp(min=1.0)
                pooled = (hidden * mask.unsqueeze(1)).sum(dim=0) / denom
            else:
                pooled = hidden.mean(dim=0)
        else:
            pooled = hidden.view(-1)
        return F.normalize(pooled, p=2, dim=0)


class HDLxGraphRetriever:
    """HDLxGraph (Neo4j) 检索器"""

    def __init__(
        self,
        uri: str,
        user: str,
        password: str,
        mode: str = "block",
        model_name: str = "Salesforce/codet5p-110m-embedding",
        device: str | None = None,
    ):
        self.uri = uri
        self.user = user
        self.password = password
        self.mode = mode
        self.model_name = model_name
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.graph = None
        self.tokenizer = None
        self.model = None

    def build_index(self):
        """连接Neo4j并加载embedding模型"""
        try:
            from py2neo import Graph
        except ImportError as exc:
            raise ImportError(
                "py2neo is required for HDLxGraph retrieval. Install with: pip install py2neo"
            ) from exc

        if self.graph is None:
            self.graph = Graph(self.uri, auth=(self.user, self.password))

        if self.tokenizer is None or self.model is None:
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_name, trust_remote_code=True
            )
            self.model = AutoModel.from_pretrained(
                self.model_name, trust_remote_code=True
            ).to(self.device)
            self.model.eval()

    def _encode(self, text: str) -> torch.Tensor:
        inputs = self.tokenizer(text, return_tensors="pt")["input_ids"].to(self.device)
        with torch.no_grad():
            embedding = self.model(inputs)[0]
        return embedding

    def search(self, query: str, top_k: int = 5) -> List[Dict[str, Any]]:
        """在Neo4j中检索最相关的节点"""
        if self.graph is None or self.model is None:
            raise ValueError("Index not built. Call build_index() first.")

        query_embedding = self._encode(query).tolist()

        if self.mode == "module":
            cypher = """
            MATCH (m:Module)
            WHERE m.code_embedding IS NOT NULL
            WITH m, gds.similarity.cosine(m.code_embedding, $query_embedding) AS similarity
            RETURN m.name AS name, m.code AS code, similarity
            ORDER BY similarity DESC
            LIMIT $top_k
            """
        elif self.mode == "signal":
            cypher = """
            MATCH (s:Signal)
            WHERE s.context_embedding IS NOT NULL
            WITH s, gds.similarity.cosine(s.context_embedding, $query_embedding) AS similarity
            RETURN s.name AS signal_name, s.context AS context, similarity
            ORDER BY similarity DESC
            LIMIT $top_k
            """
        else:
            cypher = """
            MATCH (b:Block)
            WHERE b.code_embedding IS NOT NULL
            WITH b, gds.similarity.cosine(b.code_embedding, $query_embedding) AS similarity
            RETURN b.id AS id, b.type AS type, b.code AS block_code, similarity
            ORDER BY similarity DESC
            LIMIT $top_k
            """

        return list(
            self.graph.run(
                cypher, query_embedding=query_embedding, top_k=top_k
            ).data()
        )


class BenchmarkRunner:
    """Benchmark运行器"""

    def __init__(
        self,
        benchmark_path: str,
        database_path: str,
        output_dir: str = "benchmark_results",
        chunk_size: int = 1000,
        overlap: int = 200,
        hdlgraph_uri: str = "bolt://localhost:7687",
        hdlgraph_user: str = "neo4j",
        hdlgraph_password: str = "neo4j",
        hdlgraph_mode: str = "block",
        hdlgraph_model: str = "Salesforce/codet5p-110m-embedding",
        hdlgraph_device: str | None = None,
    ):
        self.benchmark_path = Path(benchmark_path)
        self.database_path = Path(database_path)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.hdlgraph_uri = hdlgraph_uri
        self.hdlgraph_user = hdlgraph_user
        self.hdlgraph_password = hdlgraph_password
        self.hdlgraph_mode = hdlgraph_mode
        self.hdlgraph_model = hdlgraph_model
        self.hdlgraph_device = hdlgraph_device

        # 加载代码数据库
        self.code_db = CodeDatabase(database_path, chunk_size, overlap)
        self.code_db.load_database()

        # 初始化检索器
        self.bm25_retriever = BM25Retriever(self.code_db)
        self.embedding_retriever = EmbeddingRetriever(self.code_db)
        self.hdlgraph_retriever = None

    def load_benchmark(self) -> List[Dict[str, Any]]:
        """加载benchmark数据"""
        print(f"Loading benchmark from {self.benchmark_path}")

        with open(self.benchmark_path, "r", encoding="utf-8") as f:
            benchmark_data = json.load(f)

        print(f"Loaded {len(benchmark_data)} benchmark samples")
        return benchmark_data

    def build_indices(self, method: str):
        """构建检索索引"""
        print("Building retrieval indices...")
        use_bm25 = method in ["both", "bm25", "all"]
        use_embedding = method in ["both", "embedding", "all"]
        use_hdlgraph = method in ["hdlgraph", "all"]

        # 构建BM25索引
        if use_bm25:
            self.bm25_retriever.build_index()

        # 构建embedding索引
        if use_embedding and self.embedding_retriever:
            self.embedding_retriever.build_index()

        if use_hdlgraph:
            self.hdlgraph_retriever = HDLxGraphRetriever(
                uri=self.hdlgraph_uri,
                user=self.hdlgraph_user,
                password=self.hdlgraph_password,
                mode=self.hdlgraph_mode,
                model_name=self.hdlgraph_model,
                device=self.hdlgraph_device,
            )
            self.hdlgraph_retriever.build_index()

    def evaluate_similarity(self, target_code: str, retrieved_code: str) -> float:
        """评估检索结果与目标代码的相似度（使用 ROUGE-2）"""
        # 清洗代码
        reference = [target_code]  # 转为 token 列表
        candidate = [retrieved_code]

        # 使用 ROUGE-2 作为评分标准
        rouge = Rouge()
        scores = rouge.get_scores(reference, candidate)
        return scores[0]["rouge-2"]["f"]  # 返回 ROUGE-2 的 F1 分数

    # def evaluate_similarity(self, target_code: str, retrieved_code: str) -> float:
    #     """评估检索结果与目标代码的相似度"""
    #     # 简单的文本相似度评估
    #     target_clean = re.sub(r"\s+", " ", target_code.strip())
    #     retrieved_clean = re.sub(r"\s+", " ", retrieved_code.strip())
    #
    #     if len(target_clean) == 0 or len(retrieved_clean) == 0:
    #         return 0.0
    #
    #     # 计算Jaccard相似度
    #     target_words = set(target_clean.split())
    #     retrieved_words = set(retrieved_clean.split())
    #
    #     if len(target_words) == 0 or len(retrieved_words) == 0:
    #         return 0.0
    #
    #     intersection = len(target_words.intersection(retrieved_words))
    #     union = len(target_words.union(retrieved_words))
    #
    #     return intersection / union if union > 0 else 0.0
    #
    def inject_benchmark_targets(self, benchmark_data: List[Dict[str, Any]]) -> None:
        """将benchmark的目标代码注入语料库，便于对齐评估"""
        injected = 0
        for i, sample in enumerate(benchmark_data):
            code = sample.get("code", "")
            if not code or not code.strip():
                continue
            self.code_db.code_snippets.append(code)
            self.code_db.snippet_info.append(
                {
                    "file": f"__benchmark__/sample_{i}",
                    "block_id": -1,
                    "content": code,
                    "injected": True,
                }
            )
            injected += 1
        print(f"Injected {injected} benchmark targets into corpus")

    def run_benchmark(
        self,
        method: str = "both",
        top_k: int = 5,
        metric: str = "rouge",
        mrr_threshold: float = 0.1,
        inject_targets: bool = True,
    ):
        """运行benchmark"""
        print(f"Running benchmark with method: {method}")
        use_rouge = metric in ["rouge", "both"]
        use_mrr = metric in ["mrr", "both"]
        use_similarity = use_rouge or use_mrr
        use_bm25 = method in ["both", "bm25", "all"]
        use_embedding = method in ["both", "embedding", "all"]
        use_hdlgraph = method in ["hdlgraph", "all"]

        # 加载benchmark数据
        benchmark_data = self.load_benchmark()

        if inject_targets:
            self.inject_benchmark_targets(benchmark_data)

        # 构建索引
        self.build_indices(method)

        # 准备结果存储
        results = []

        # 运行检索
        for i, sample in enumerate(benchmark_data):
            print(f"Processing sample {i+1}/{len(benchmark_data)}")

            query = sample["description"]
            target_code = sample["code"]

            sample_result = {
                "sample_id": i,
                "query": query,
                "target_code_length": len(target_code),
                "target_code_preview": (
                    target_code[:100] + "..." if len(target_code) > 100 else target_code
                ),
            }

            # BM25检索
            if use_bm25:
                try:
                    bm25_results = self.bm25_retriever.search(query, top_k)
                    bm25_retrieved = []
                    bm25_rr = 0.0

                    for rank, (idx, score) in enumerate(bm25_results, start=1):
                        retrieved_code = self.code_db.snippet_info[idx]["content"]
                        similarity = (
                            self.evaluate_similarity(target_code, retrieved_code)
                            if use_similarity
                            else 0.0
                        )
                        if use_mrr and bm25_rr == 0.0 and similarity >= mrr_threshold:
                            bm25_rr = 1.0 / rank

                        bm25_retrieved.append(
                            {
                                "file": self.code_db.snippet_info[idx]["file"],
                                "block_id": self.code_db.snippet_info[idx]["block_id"],
                                "score": score,
                                "similarity": similarity,
                                "code_preview": (
                                    retrieved_code[:100] + "..."
                                    if len(retrieved_code) > 100
                                    else retrieved_code
                                ),
                            }
                        )

                    sample_result["bm25_results"] = bm25_retrieved
                    sample_result["bm25_best_similarity"] = (
                        max([r["similarity"] for r in bm25_retrieved])
                        if bm25_retrieved
                        else 0.0
                    )
                    if use_mrr:
                        sample_result["bm25_rr"] = bm25_rr

                except Exception as e:
                    print(f"Error in BM25 retrieval for sample {i}: {e}")
                    sample_result["bm25_results"] = []
                    sample_result["bm25_best_similarity"] = 0.0
                    if use_mrr:
                        sample_result["bm25_rr"] = 0.0

            # Embedding检索
            if use_embedding and self.embedding_retriever:
                try:
                    embedding_results = self.embedding_retriever.search(query, top_k)
                    embedding_retrieved = []
                    embedding_rr = 0.0

                    for rank, (idx, score) in enumerate(embedding_results, start=1):
                        retrieved_code = self.code_db.snippet_info[idx]["content"]
                        similarity = (
                            self.evaluate_similarity(target_code, retrieved_code)
                            if use_similarity
                            else 0.0
                        )
                        if (
                            use_mrr
                            and embedding_rr == 0.0
                            and similarity >= mrr_threshold
                        ):
                            embedding_rr = 1.0 / rank

                        embedding_retrieved.append(
                            {
                                "file": self.code_db.snippet_info[idx]["file"],
                                "block_id": self.code_db.snippet_info[idx]["block_id"],
                                "score": score,
                                "similarity": similarity,
                                "code_preview": (
                                    retrieved_code[:100] + "..."
                                    if len(retrieved_code) > 100
                                    else retrieved_code
                                ),
                            }
                        )

                    sample_result["embedding_results"] = embedding_retrieved
                    sample_result["embedding_best_similarity"] = (
                        max([r["similarity"] for r in embedding_retrieved])
                        if embedding_retrieved
                        else 0.0
                    )
                    if use_mrr:
                        sample_result["embedding_rr"] = embedding_rr

                except Exception as e:
                    print(f"Error in embedding retrieval for sample {i}: {e}")
                    sample_result["embedding_results"] = []
                    sample_result["embedding_best_similarity"] = 0.0
                    if use_mrr:
                        sample_result["embedding_rr"] = 0.0

            if use_hdlgraph and self.hdlgraph_retriever:
                try:
                    hdlgraph_results = self.hdlgraph_retriever.search(query, top_k)
                    hdlgraph_retrieved = []
                    hdlgraph_rr = 0.0

                    for rank, item in enumerate(hdlgraph_results, start=1):
                        retrieved_code = (
                            item.get("block_code")
                            or item.get("code")
                            or ""
                        )
                        similarity = (
                            self.evaluate_similarity(target_code, retrieved_code)
                            if use_similarity and retrieved_code
                            else 0.0
                        )
                        if use_mrr and hdlgraph_rr == 0.0 and similarity >= mrr_threshold:
                            hdlgraph_rr = 1.0 / rank

                        hdlgraph_retrieved.append(
                            {
                                "node_id": item.get("id")
                                or item.get("name")
                                or item.get("signal_name")
                                or "",
                                "node_type": self.hdlgraph_mode,
                                "score": item.get("similarity", 0.0),
                                "similarity": similarity,
                                "code_preview": (
                                    retrieved_code[:100] + "..."
                                    if len(retrieved_code) > 100
                                    else retrieved_code
                                ),
                            }
                        )

                    sample_result["hdlgraph_results"] = hdlgraph_retrieved
                    sample_result["hdlgraph_best_similarity"] = (
                        max([r["similarity"] for r in hdlgraph_retrieved])
                        if hdlgraph_retrieved
                        else 0.0
                    )
                    if use_mrr:
                        sample_result["hdlgraph_rr"] = hdlgraph_rr

                except Exception as e:
                    print(f"Error in HDLxGraph retrieval for sample {i}: {e}")
                    sample_result["hdlgraph_results"] = []
                    sample_result["hdlgraph_best_similarity"] = 0.0
                    if use_mrr:
                        sample_result["hdlgraph_rr"] = 0.0

            results.append(sample_result)

        # 保存结果
        self.save_results(results, method, metric, mrr_threshold)

        # 打印统计信息
        self.print_statistics(results, method, metric, mrr_threshold)

    def save_results(
        self,
        results: List[Dict],
        method: str,
        metric: str,
        mrr_threshold: float,
    ):
        """保存结果到CSV文件"""
        timestamp = time.strftime("%Y%m%d_%H%M%S")
        use_rouge = metric in ["rouge", "both"]
        use_mrr = metric in ["mrr", "both"]
        use_bm25 = method in ["both", "bm25", "all"]
        use_embedding = method in ["both", "embedding", "all"]
        use_hdlgraph = method in ["hdlgraph", "all"]

        # 保存详细结果
        detailed_filename = (
            self.output_dir / f"detailed_results_{method}_{timestamp}.csv"
        )
        with open(detailed_filename, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)

            # 写入表头
            headers = ["sample_id", "query", "target_code_preview"]
            if use_bm25 and use_rouge:
                headers.extend(
                    ["bm25_best_similarity", "bm25_best_file", "bm25_best_score"]
                )
            if use_bm25 and use_mrr:
                headers.append("bm25_rr")
            if use_embedding and use_rouge:
                headers.extend(
                    [
                        "embedding_best_similarity",
                        "embedding_best_file",
                        "embedding_best_score",
                    ]
                )
            if use_embedding and use_mrr:
                headers.append("embedding_rr")
            if use_hdlgraph and use_rouge:
                headers.extend(
                    [
                        "hdlgraph_best_similarity",
                        "hdlgraph_best_id",
                        "hdlgraph_best_score",
                    ]
                )
            if use_hdlgraph and use_mrr:
                headers.append("hdlgraph_rr")

            writer.writerow(headers)

            # 写入数据
            for result in results:
                row = [
                    result["sample_id"],
                    result["query"],
                    result["target_code_preview"],
                ]

                if use_bm25 and use_rouge:
                    if result["bm25_results"]:
                        best_bm25 = max(
                            result["bm25_results"], key=lambda x: x["similarity"]
                        )
                        row.extend(
                            [best_bm25["similarity"], best_bm25["file"], best_bm25["score"]]
                        )
                    else:
                        row.extend([0.0, "", 0.0])
                if use_bm25 and use_mrr:
                    row.append(result.get("bm25_rr", 0.0))

                if use_embedding and use_rouge:
                    if result["embedding_results"]:
                        best_embedding = max(
                            result["embedding_results"], key=lambda x: x["similarity"]
                        )
                        row.extend(
                            [
                                best_embedding["similarity"],
                                best_embedding["file"],
                                best_embedding["score"],
                            ]
                        )
                    else:
                        row.extend([0.0, "", 0.0])
                if use_embedding and use_mrr:
                    row.append(result.get("embedding_rr", 0.0))

                if use_hdlgraph and use_rouge:
                    if result["hdlgraph_results"]:
                        best_hdlgraph = max(
                            result["hdlgraph_results"], key=lambda x: x["similarity"]
                        )
                        row.extend(
                            [
                                best_hdlgraph["similarity"],
                                best_hdlgraph["node_id"],
                                best_hdlgraph["score"],
                            ]
                        )
                    else:
                        row.extend([0.0, "", 0.0])
                if use_hdlgraph and use_mrr:
                    row.append(result.get("hdlgraph_rr", 0.0))

                writer.writerow(row)

        print(f"Detailed results saved to: {detailed_filename}")

        # 保存完整结果（包含所有top-k结果）
        full_filename = self.output_dir / f"full_results_{method}_{timestamp}.json"
        with open(full_filename, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False)

        print(f"Full results saved to: {full_filename}")

    def print_statistics(
        self,
        results: List[Dict],
        method: str,
        metric: str,
        mrr_threshold: float,
    ):
        """打印统计信息"""
        print("\n" + "=" * 60)
        print("BENCHMARK STATISTICS")
        print("=" * 60)
        use_rouge = metric in ["rouge", "both"]
        use_mrr = metric in ["mrr", "both"]
        use_bm25 = method in ["both", "bm25", "all"]
        use_embedding = method in ["both", "embedding", "all"]
        use_hdlgraph = method in ["hdlgraph", "all"]

        if use_bm25:
            print(f"\nBM25 Results:")
            if use_rouge:
                bm25_similarities = [r["bm25_best_similarity"] for r in results]
                print(f"  Average similarity: {np.mean(bm25_similarities):.4f}")
                print(f"  Median similarity: {np.median(bm25_similarities):.4f}")
                print(f"  Max similarity: {np.max(bm25_similarities):.4f}")
                print(f"  Min similarity: {np.min(bm25_similarities):.4f}")
                print(f"  Std deviation: {np.std(bm25_similarities):.4f}")
                # MRR@0.1
                reciprocal_ranks = []
                for r in results:
                    ranks = [
                        i + 1
                        for i, res in enumerate(r.get("bm25_results", []))
                        if res["similarity"] >= 0.1
                    ]
                    if ranks:
                        reciprocal_ranks.append(1.0 / ranks[0])
                    else:
                        reciprocal_ranks.append(0.0)
                mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)
                print(f"  MRR@0.1: {mrr:.4f}")
            if use_mrr:
                bm25_rr = [r.get("bm25_rr", 0.0) for r in results]
                print(f"  MRR@{mrr_threshold:.2f}: {np.mean(bm25_rr):.4f}")

        if use_embedding and self.embedding_retriever:
            print(f"\nEmbedding Results:")
            if use_rouge:
                embedding_similarities = [r["embedding_best_similarity"] for r in results]
                print(f"  Average similarity: {np.mean(embedding_similarities):.4f}")
                print(f"  Median similarity: {np.median(embedding_similarities):.4f}")
                print(f"  Max similarity: {np.max(embedding_similarities):.4f}")
                print(f"  Min similarity: {np.min(embedding_similarities):.4f}")
                print(f"  Std deviation: {np.std(embedding_similarities):.4f}")

                # 计算不同相似度阈值下的命中率
                reciprocal_ranks = []
                for r in results:
                    ranks = [
                        i + 1
                        for i, res in enumerate(r.get("embedding_results", []))
                        if res["similarity"] >= 0.05
                    ]
                    if ranks:
                        reciprocal_ranks.append(1.0 / ranks[0])
                    else:
                        reciprocal_ranks.append(0.0)
                mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)
                print(f"   MRR@0.1: {mrr:.4f}")
            if use_mrr:
                embedding_rr = [r.get("embedding_rr", 0.0) for r in results]
                print(f"  MRR@{mrr_threshold:.2f}: {np.mean(embedding_rr):.4f}")

        if use_hdlgraph:
            print(f"\nHDLxGraph Results:")
            if use_rouge:
                hdlgraph_similarities = [r["hdlgraph_best_similarity"] for r in results]
                print(f"  Average similarity: {np.mean(hdlgraph_similarities):.4f}")
                print(f"  Median similarity: {np.median(hdlgraph_similarities):.4f}")
                print(f"  Max similarity: {np.max(hdlgraph_similarities):.4f}")
                print(f"  Min similarity: {np.min(hdlgraph_similarities):.4f}")
                print(f"  Std deviation: {np.std(hdlgraph_similarities):.4f}")

                reciprocal_ranks = []
                for r in results:
                    ranks = [
                        i + 1
                        for i, res in enumerate(r.get("hdlgraph_results", []))
                        if res["similarity"] >= 0.1
                    ]
                    if ranks:
                        reciprocal_ranks.append(1.0 / ranks[0])
                    else:
                        reciprocal_ranks.append(0.0)
                mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)
                print(f"  MRR@0.1: {mrr:.4f}")
            if use_mrr:
                hdlgraph_rr = [r.get("hdlgraph_rr", 0.0) for r in results]
                print(f"  MRR@{mrr_threshold:.2f}: {np.mean(hdlgraph_rr):.4f}")

        if method == "both":
            # 比较两种方法
            bm25_similarities = [r["bm25_best_similarity"] for r in results]
            embedding_similarities = [r["embedding_best_similarity"] for r in results]

            print(f"\nComparison:")
            bm25_wins = sum(
                1
                for i in range(len(results))
                if bm25_similarities[i] > embedding_similarities[i]
            )
            embedding_wins = sum(
                1
                for i in range(len(results))
                if embedding_similarities[i] > bm25_similarities[i]
            )
            ties = len(results) - bm25_wins - embedding_wins

            print(f"  BM25 wins: {bm25_wins}")
            print(f"  Embedding wins: {embedding_wins}")
            print(f"  Ties: {ties}")

            if bm25_wins + embedding_wins > 0:
                bm25_win_rate = bm25_wins / (bm25_wins + embedding_wins)
                embedding_win_rate = embedding_wins / (bm25_wins + embedding_wins)
                print(f"  BM25 win rate: {bm25_win_rate:.4f}")
                print(f"  Embedding win rate: {embedding_win_rate:.4f}")


def main():
    parser = argparse.ArgumentParser(description="Code Location Benchmark Runner")
    parser.add_argument(
        "--benchmark",
        default="benchmark_generation/generated/blocks.json",
        help="Path to benchmark JSON file",
    )
    parser.add_argument(
        "--database", default="database", help="Path to code database directory"
    )
    parser.add_argument(
        "--method",
        choices=["bm25", "embedding", "both", "hdlgraph", "all"],
        default="both",
        help="Retrieval method to use",
    )
    parser.add_argument(
        "--metric",
        choices=["rouge", "mrr", "both"],
        default="rouge",
        help="Evaluation metric to use",
    )
    parser.add_argument(
        "--mrr-threshold",
        type=float,
        default=0.1,
        help="Similarity threshold for counting a hit in MRR",
    )
    parser.add_argument(
        "--embedding-model",
        default=None,
        help="Embedding model to use (see --list-models for options)",
    )
    parser.add_argument(
        "--top-k", type=int, default=5, help="Number of top results to retrieve"
    )
    parser.add_argument(
        "--output-dir", default="benchmark_results", help="Output directory for results"
    )
    parser.add_argument(
        "--list-models",
        action="store_true",
        help="List available embedding models and exit",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=1000,
        help="Size of code chunks in characters (default: 1000)",
    )
    parser.add_argument(
        "--overlap",
        type=int,
        default=200,
        help="Overlap between chunks in characters (default: 200)",
    )
    parser.add_argument(
        "--neo4j-uri",
        default="bolt://localhost:7687",
        help="Neo4j URI for HDLxGraph retrieval",
    )
    parser.add_argument(
        "--neo4j-user",
        default="neo4j",
        help="Neo4j username for HDLxGraph retrieval",
    )
    parser.add_argument(
        "--neo4j-password",
        default="neo4j123",
        help="Neo4j password for HDLxGraph retrieval",
    )
    parser.add_argument(
        "--hdlgraph-mode",
        choices=["block", "module", "signal"],
        default="block",
        help="HDLxGraph retrieval mode (block/module/signal)",
    )
    parser.add_argument(
        "--hdlgraph-model",
        default="Salesforce/codet5p-110m-embedding",
        help="Embedding model for HDLxGraph retrieval",
    )
    parser.add_argument(
        "--hdlgraph-device",
        default=None,
        help="Device for HDLxGraph embedding model (e.g., cuda, cpu)",
    )

    args = parser.parse_args()

    # 如果请求列出模型，显示可用模型并退出
    if args.list_models:
        if MODEL_CONFIG_AVAILABLE:
            from embedding_models import list_available_models

            list_available_models()
        else:
            print("Model configuration not available. Using default models.")
            print("Available models:")
            print("  - Salesforce/codet5p-220m (default)")
            print("  - Salesforce/codet5p-770m")
            print("  - Salesforce/codet5p-110m")
            print("  - microsoft/codebert-base")
            print("  - microsoft/graphcodebert-base")
            print("  - all-mpnet-base-v2")
            print("  - all-MiniLM-L6-v2")
        return

    # 检查文件是否存在
    if not Path(args.benchmark).exists():
        print(f"Error: Benchmark file not found: {args.benchmark}")
        return

    if not Path(args.database).exists():
        print(f"Error: Database directory not found: {args.database}")
        return

    # 运行benchmark
    runner = BenchmarkRunner(
        args.benchmark,
        args.database,
        args.output_dir,
        args.chunk_size,
        args.overlap,
        args.neo4j_uri,
        args.neo4j_user,
        args.neo4j_password,
        args.hdlgraph_mode,
        args.hdlgraph_model,
        args.hdlgraph_device,
    )

    # 如果指定了embedding模型，更新embedding检索器
    if args.embedding_model and args.method in ["embedding", "both"]:
        if runner.embedding_retriever:
            runner.embedding_retriever = EmbeddingRetriever(
                runner.code_db, args.embedding_model
            )

    runner.run_benchmark(
        args.method,
        args.top_k,
        args.metric,
        args.mrr_threshold,
    )


if __name__ == "__main__":
    main()

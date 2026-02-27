import re
from typing import Iterable, List, Sequence

from rank_bm25 import BM25Okapi
from transformers import AutoModel, AutoTokenizer
import torch
import torch.nn.functional as F


_TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]+")


def _tokenize(text: str) -> List[str]:
    return _TOKEN_RE.findall(text.lower())


def _prepare_corpus(documents: Iterable) -> List[str]:
    if isinstance(documents, dict):
        file_paths = list(documents.keys())
        contents = list(documents.values())
    else:
        file_paths = []
        contents = list(documents) if documents else []

    blocks: List[str] = []
    if file_paths:
        try:
            from benchmark.search.extractor import VerilogBlockExtractor
        except Exception:
            VerilogBlockExtractor = None
        if VerilogBlockExtractor:
            extractor = VerilogBlockExtractor()
            for path in file_paths:
                try:
                    blocks_data, _ = extractor.blocks_and_signals(path)
                except Exception:
                    blocks_data = None
                if not blocks_data:
                    continue
                for _, (_, block_code) in blocks_data.items():
                    if block_code:
                        blocks.append(block_code)

    return blocks if blocks else contents


def _format_results(results: Sequence[dict], label: str) -> str:
    if not results:
        return "Did not find match result"
    output = []
    for idx, item in enumerate(results, 1):
        output.append(f"\nResult {idx} ({label}: {item['score']:.3f}):")
        code = item["code"]
        snippet = code[:200] + "..." if len(code) > 200 else code
        output.append(f"code:\n{snippet}")
        output.append("-" * 50)
    return "\n".join(output)


class BM25Baseline:
    def __init__(self, documents: Iterable):
        self.corpus = _prepare_corpus(documents)
        tokenized = [_tokenize(doc) for doc in self.corpus]
        self.bm25 = BM25Okapi(tokenized) if tokenized else None

    def search(self, query: str, top_k: int = 10, return_type: str = "text"):
        if not self.corpus or not self.bm25:
            return [] if return_type in {"list", "raw"} else "Did not find match result"
        query_tokens = _tokenize(query)
        scores = self.bm25.get_scores(query_tokens)
        top_k = min(top_k, len(self.corpus))
        top_indices = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)[:top_k]
        results = [
            {"code": self.corpus[idx], "score": float(scores[idx])}
            for idx in top_indices
        ]
        if return_type == "raw":
            return results
        if return_type == "list":
            return [item["code"] for item in results]
        return _format_results(results, "Score")


class EmbeddingBaseline:
    def __init__(
        self,
        documents: Iterable,
        model_name: str = "Salesforce/codet5p-110m-embedding",
        device: str = None,
        batch_size: int = 16,
    ):
        self.corpus = _prepare_corpus(documents)
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
        self.model = AutoModel.from_pretrained(model_name, trust_remote_code=True).to(self.device)
        self.model.eval()
        self.embeddings = self._encode_corpus(batch_size)

    def _encode_texts(self, texts: Sequence[str]) -> torch.Tensor:
        inputs = self.tokenizer(
            list(texts),
            return_tensors="pt",
            truncation=True,
            max_length=512,
            padding=True,
        )
        inputs = {k: v.to(self.device) for k, v in inputs.items()}
        with torch.no_grad():
            outputs = self.model(**inputs)
        hidden = outputs.last_hidden_state if hasattr(outputs, "last_hidden_state") else outputs[0]
        pooled = hidden.mean(dim=1)
        return F.normalize(pooled, p=2, dim=1)

    def _encode_corpus(self, batch_size: int) -> torch.Tensor:
        if not self.corpus:
            return torch.empty((0, 1))
        batches = []
        for start in range(0, len(self.corpus), batch_size):
            batch = self.corpus[start:start + batch_size]
            embeddings = self._encode_texts(batch).cpu()
            batches.append(embeddings)
        return torch.cat(batches, dim=0)

    def search(self, query: str, top_k: int = 10, return_type: str = "text"):
        if not self.corpus:
            return [] if return_type in {"list", "raw"} else "Did not find match result"
        top_k = min(top_k, len(self.corpus))
        query_embedding = self._encode_texts([query]).cpu()
        scores = torch.matmul(self.embeddings, query_embedding[0])
        values, indices = torch.topk(scores, k=top_k)
        results = [
            {"code": self.corpus[idx], "score": float(values[i])}
            for i, idx in enumerate(indices.tolist())
        ]
        if return_type == "raw":
            return results
        if return_type == "list":
            return [item["code"] for item in results]
        return _format_results(results, "Similarity")

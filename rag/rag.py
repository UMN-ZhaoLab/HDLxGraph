from rag.baselines import BM25Baseline, EmbeddingBaseline
from rag.neo4j_rag import Neo4jRAG

class RAG_Agent:
    def __init__(self, rag_type, generate_func, model):
        self.rag_type = rag_type
        self.rag_agent = None
        self.model = model
        self.generate_func = generate_func

    def build_database(self, documents):
        if self.rag_type == "HDLxGraph":
            self.rag_agent = Neo4jRAG(self.generate_func)
            paths = list(documents.keys()) if isinstance(documents, dict) else list(documents)
            self.rag_agent.build_database_from_scala(paths)
        elif self.rag_type == "bm25":
            self.rag_agent = BM25Baseline(documents)
        elif self.rag_type == "similarity":
            self.rag_agent = EmbeddingBaseline(documents)

    def retrieve(self, prompt, k=2, return_type="text", mode="ast", **kwargs):
        if not self.rag_agent:
            return [] if return_type in {"list", "raw"} else ""
        if isinstance(self.rag_agent, Neo4jRAG):
            return self.rag_agent.search(
                self.model,
                prompt,
                top_k=k,
                return_type=return_type,
                mode=mode,
                **kwargs,
            )
        return self.rag_agent.search(prompt, top_k=k, return_type=return_type)

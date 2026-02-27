# netlist2neo4j embeddings

Local CodeT5+ support:
- Install deps: `pip install transformers torch` (use a CUDA build of torch if you want GPU).
- Update config JSON: set `"generateEmbeddings": true`, `"embeddings.enabled": true`, `"embeddings.provider": "codet5"`, `"embeddings.model": "Salesforce/codet5p-110m-embedding"`.
- The Scala importer will call `scripts/codet5_embed.py` to generate embeddings locally; it auto-selects GPU if available.
- Adjust `"embeddings.batchSize"` to control Python-side batching.

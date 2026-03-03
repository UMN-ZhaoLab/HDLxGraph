# HDLxGraph

HDLxGraph parses Verilog/SystemVerilog code and imports it into Neo4j as AST/DFG graph structures, then provides retrieval capabilities (AST semantic retrieval + DFG signal-flow tracing).

## 1. Requirements

- Linux/macOS (Linux recommended)
- Python `3.10+`
- Java/JDK `17+`
- Neo4j `5.x`
- Yosys (used for Verilog parsing flow)
- Mill (current project version: `0.12.5`)

## 2. Install Dependencies

Run in the repository root:

```bash
python -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install py2neo pyverilog pydantic transformers torch rank_bm25 openai anthropic ollama rouge tqdm numpy scikit-learn
```

Install Mill (if not already installed):

```bash
curl -L -o mill https://github.com/com-lihaoyi/mill/releases/download/0.12.5/0.12.5
chmod +x mill
```

## 3. Start and Configure Neo4j

Start Neo4j first, then set environment variables (these override `config/config.json`):

```bash
export NEO4J_URI="bolt://localhost:7687"
export NEO4J_USER="neo4j"
export NEO4J_PASSWORD="your_password"
```

## 4. Create Local Config

`config/config.json` is intentionally ignored by Git. Create it from the template first:

```bash
cp config/config-example.json config/config.json
```

Then edit `config/config.json` for your environment.

Key fields:

- `processing.clearDatabase`: whether to clear the database before import.
- `processing.importAST`: whether to import AST.
- `processing.importDFG`: whether to import DFG.
- `processing.alignDFGToAST`: whether to build alignment edges from DFG to AST.
- `processing.generateEmbeddings` + `embeddings.enabled`: AST layered retrieval requires embeddings; recommended to enable.
- `batchProcessing.enabled`: whether to enable batch import.
- `neo4j.password`: your local Neo4j password.
- `embeddings.apiKey`: your embedding API key (if using remote embeddings).

If you want to use LLM query decomposition in AST retrieval, configure model keys in `generation/generation.py` (for example `qwen_key` or `anthropic_key`).

## 5. Download Verilog Database

The `hdlsearch/database/verilog_database` directory is not stored in Git because of its size.
Download and extract it before import.

The script uses a fixed repo mapping and a fixed destination path:

- source repos: defined in `scripts/download_verilog_database.py` as `REPO_MAP`
- destination path: `TARGET_BASE = hdlsearch/database/verilog_database`

Run:

```bash
python scripts/download_verilog_database.py
```

Note: unresolved directory names are kept in `PENDING_REPOS` in the same script.

## 6. Compile Scala Importer

```bash
./mill --no-server netlist2neo4j.compile
```

## 7. Import Full Graph Data (AST + DFG)

### 7.1 Generate Verilog File List

```bash
python list_verilog_paths.py \
  --root hdlsearch/database/verilog_database \
  --output verilog_files.txt \
  --absolute
```

### 7.2 Batch Import to Neo4j

```bash
./mill --no-server netlist2neo4j.run --config config/config.json verilog_files.txt
```

You can also import a single file:

```bash
./mill --no-server netlist2neo4j.run --config config/config.json path/to/design.v
```

## 8. Run Retrieval (AST + DFG)

After database import is finished, run:

```python
from generation.generation import main_generation
from rag.neo4j_rag import Neo4jRAG

rag = Neo4jRAG(main_generation)

# AST retrieval: natural-language query -> module/block/signal decomposition -> layered recall + rerank
ast_result = rag.search(
    model="qwen",
    description="Find the logic related to FIFO write pointer updates",
    mode="ast",
    top_k=5,
)
print(ast_result)

# DFG retrieval: search directly by signal name and print upstream signal-flow tree
dfg_result = rag.search(
    model="qwen",
    description="wr_ptr",
    mode="dfg",
    return_type="text_tree",
    max_depth=4,
)
print(dfg_result)
```

## 9. Optional: Run Tests

```bash
./mill --no-server netlist2neo4j.test
```

## Citation

```
@misc{zheng2025hdlxgraphbridginglargelanguage,
      title={HDLxGraph: Bridging Large Language Models and HDL Repositories via HDL Graph Databases}, 
      author={Pingqing Zheng and Jiayin Qin and Fuqi Zhang and Shang Wu and Yu Cao and Caiwen Ding and Yang and Zhao},
      year={2025},
      eprint={2505.15701},
      archivePrefix={arXiv},
      primaryClass={cs.AR},
      url={https://arxiv.org/abs/2505.15701}, 
}
```

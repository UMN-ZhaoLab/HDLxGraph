import argparse
import json
from typing import List

import torch
from transformers import AutoTokenizer, AutoModel


def encode_texts(texts: List[str], model_name: str, batch_size: int) -> List[List[float]]:
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    tokenizer = AutoTokenizer.from_pretrained(model_name, trust_remote_code=True)
    model = AutoModel.from_pretrained(model_name, trust_remote_code=True).to(device)
    model.eval()

    embeddings: List[List[float]] = []
    with torch.no_grad():
        for start in range(0, len(texts), batch_size):
            batch = texts[start : start + batch_size]
            encoded = tokenizer(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=512,
            ).to(device)
            outputs = model(**encoded)
            hidden = outputs.last_hidden_state if hasattr(outputs, "last_hidden_state") else outputs[0]
            pooled = hidden.mean(dim=1)
            normalized = torch.nn.functional.normalize(pooled, p=2, dim=1)
            embeddings.extend(normalized.cpu().tolist())
    return embeddings


def main():
    parser = argparse.ArgumentParser(description="Generate CodeT5+ embeddings locally")
    parser.add_argument("--model", default="Salesforce/codet5p-110m-embedding", help="Hugging Face model id or local path")
    parser.add_argument("--input-file", required=True, help="Path to JSON list of strings to embed")
    parser.add_argument("--output-file", required=True, help="Where to write JSON list of embeddings")
    parser.add_argument("--batch-size", type=int, default=8, help="Batch size for inference")
    args = parser.parse_args()

    with open(args.input_file, "r", encoding="utf-8") as f:
        texts = json.load(f)
        if not isinstance(texts, list):
            raise ValueError("Input JSON must be a list of strings")

    embeddings = encode_texts(texts, args.model, max(1, args.batch_size))

    with open(args.output_file, "w", encoding="utf-8") as f:
        json.dump(embeddings, f)


if __name__ == "__main__":
    main()

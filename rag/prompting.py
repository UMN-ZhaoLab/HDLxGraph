import json
import re
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field

try:
    from pydantic import ConfigDict
except ImportError:  # pragma: no cover - pydantic v1 fallback
    ConfigDict = None

_JSON_BLOCK_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)


def _find_json_blob(text: str) -> Optional[str]:
    match = _JSON_BLOCK_RE.search(text)
    if match:
        return match.group(1)

    start = text.find("{")
    if start == -1:
        return None

    depth = 0
    for idx in range(start, len(text)):
        char = text[idx]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:idx + 1]

    return None


def _coerce_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (list, tuple)):
        return " ".join(str(item).strip() for item in value if item is not None).strip()
    return str(value).strip()


class SearchQuery(BaseModel):
    module_desc: str = Field(default="", description="Module-level intent")
    block_desc: str = Field(default="", description="Block-level intent")
    signal_name: str = Field(default="", description="Signal name")

    if ConfigDict is not None:  # pragma: no cover - pydantic v2
        model_config = ConfigDict(extra="ignore")
    else:  # pragma: no cover - pydantic v1
        class Config:
            extra = "ignore"

    @classmethod
    def _schema(cls) -> Dict[str, Any]:
        if hasattr(cls, "model_json_schema"):
            return cls.model_json_schema()
        return cls.schema()

    @classmethod
    def format_instructions(cls) -> str:
        schema = json.dumps(cls._schema(), ensure_ascii=True, indent=2)
        return (
            "Return ONLY a JSON object that matches this JSON Schema.\n"
            "Do not include Markdown, commentary, or extra keys.\n"
            f"{schema}"
        )

    @classmethod
    def parse(cls, text: str) -> "SearchQuery":
        blob = _find_json_blob(text)
        if not blob:
            return cls()
        try:
            payload = json.loads(blob)
        except json.JSONDecodeError:
            return cls()
        data = {
            "module_desc": _coerce_text(payload.get("module_desc", "")),
            "block_desc": _coerce_text(payload.get("block_desc", "")),
            "signal_name": _coerce_text(
                payload.get("signal_name", payload.get("signal_desc", ""))
            ),
        }
        return cls(**data)

    def is_empty(self) -> bool:
        return not (self.module_desc or self.block_desc or self.signal_name)

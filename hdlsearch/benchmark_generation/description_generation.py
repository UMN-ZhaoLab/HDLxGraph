import json
import os
import sys
from collections import defaultdict
from itertools import islice
from typing import Any, Dict, List

from filter import find_random_nodes
from tqdm import tqdm

from utils.utils import get_code_of_module

sys.path.append("../")

import utils

block_requirements = """
You are a Verilog/Systemverilog expert. You are given a Verilog code block. Your task is to:
1. **Analyze the code structure**: Identify the type of logic (combinational, sequential, FSM, etc.) and key components (e.g., registers, multiplexers, counters).
2. **Describe the general function**: Focus on the purpose of the code (e.g., data path control, signal synchronization, state transitions) without mentioning specific signal names.
3. **Abstract technical details**: Replace signal names with generic terms like "input A", "control flag", or "state register" to emphasize functionality over implementation.
4. **Output format**: Return a concise paragraph (50–150 words) in plain text, avoiding markdown, code snippets, or technical jargon.

Example:  
"Implements a synchronous counter with reset and enable control. Increments on clock edges when enabled, resets to initial value when reset is active."
"""

signal_requirements = """
You are given a signal in a Verilog module and its associated code blocks with descriptions. Your task is to:
1. **Analyze the signal's role**: Determine whether it acts as a control flag, data path, state indicator, or other functional category.
2. **Describe the general behavior**: Explain how the signal influences the logic (e.g., "controls data flow", "triggers an event", "stores intermediate state") without mentioning the signal name.
3. **Abstract technical details**: Replace the signal name with generic terms like "control signal", "data register", or "state flag" to emphasize functionality over implementation.
4. **Output format**: Return a concise paragraph (50–150 words) in plain text, avoiding markdown, code snippets, or technical jargon.

Example:  
"Acts as a control signal to enable data transfer between two registers. Asserted during valid input conditions to synchronize data propagation."
"""

module_requirements = """
You are given a Verilog module's structural description, including its blocks, signals, and their interactions. Your task is to:
1. **Identify the module's purpose**: Infer the high-level function (e.g., "arithmetic unit", "state machine", "data router") based on its components and connections.
2. **Summarize key behavior**: Describe the module's role in the system (e.g., "coordinates data flow between peripherals", "implements a finite state machine for protocol control").
3. **Abstract implementation details**: Avoid mentioning module names, signal names, or specific implementation constructs. Focus on functional relationships and system-level impact.
4. **Output format**: Return a concise paragraph (50–150 words) in plain text, avoiding markdown, code snippets, or technical jargon.

Example:  
"Implements a finite state machine to manage bus arbitration. Coordinates access requests from multiple clients and grants priority-based access to shared resources."
"""


def block_description_generation(code):
    # return ""
    response = utils.generate_response(block_requirements, code)
    description = utils.extract_message_content(response)
    return description


def signal_description_generation(signal_name, code, description):
    # return ""
    cat_code_description = "\n\n".join(
        [f"code: {c}\ndescription: {d}" for c, d in zip(code, description)]
    )
    signal_prompt = f"signal_name: {signal_name}\n" + cat_code_description
    response = utils.generate_response(signal_requirements, signal_prompt)
    description = utils.extract_message_content(response)
    return description


def module_description_generation(code, block_description):
    # return ""
    combined_description = "\n".join(block_description)

    # 判断代码是否“不长”（例如：行数 ≤ 50）
    if code and len(code.splitlines()) <= 300:
        input_text = (
            f"Module Code:\n{code}\n\nBlock Descriptions:\n{combined_description}"
        )
    else:
        input_text = combined_description

    # 调用模型生成描述
    response = utils.generate_response(module_requirements, input_text)
    description = utils.extract_message_content(response)
    return description


def save_descriptions_to_json(
    data: List[Dict[str, Any]], filename: str = "descriptions.json"
):
    with open(filename, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"Descriptions saved to {filename}")


def map_signals_to_blocks(block_signals):
    signal_to_blocks = defaultdict(list)

    for entry in block_signals:
        block_id = entry["block_id"]
        signals = entry["signals"]

        for signal_id in signals:
            signal_to_blocks[signal_id].append(block_id)

    return dict(signal_to_blocks)


def repo_generation():
    nodes = find_random_nodes()
    results = []
    for record in tqdm(nodes, desc="Processing modules", total=len(nodes)):
        module_id = record["module_id"]
        module_filename = record["module_filename"]
        all_blocks = record["blocks"]
        block_signals = record["block_signals"]

        block_dict = {}
        for block in tqdm(
            all_blocks, desc="Block desc generation", total=len(all_blocks)
        ):
            block_id = block["id"]
            filename = block["filename"]
            begin = block["begin"]
            end = block["end"]
            code_lines = utils.get_code_of_block(filename, begin, end)
            full_code = "".join(code_lines)
            block_desc = block_description_generation(code=full_code)
            block_dict[block_id] = {"code": full_code, "description": block_desc}

        signal_to_blocks = map_signals_to_blocks(block_signals)
        all_signals = set()

        for entry in block_signals:
            all_signals.update(entry["signals"])
        signal_descriptions = []
        MAX_SIGNAL_NUM = 50
        limited_signals = islice(all_signals, MAX_SIGNAL_NUM)
        for signal_id in tqdm(
            limited_signals,
            desc="Generating signal desc",
            total=min(MAX_SIGNAL_NUM, len(all_signals)),
        ):
            associated_block_ids = signal_to_blocks.get(signal_id, [])

            code_blocks = []
            block_descs = []

            for block_id in associated_block_ids:
                if block_id in block_dict:
                    code_blocks.append(block_dict[block_id]["code"])
                    block_descs.append(block_dict[block_id]["description"])

            signal_desc = signal_description_generation(
                signal_name=signal_id, code=code_blocks, description=block_descs
            )
            signal_descriptions.append(
                {"signal": signal_id, "description": signal_desc}
            )

        module_code = get_code_of_module(module_filename)
        module_code = ""
        block_desc_list = [v["description"] for v in block_dict.values()]
        module_desc = module_description_generation(
            code=module_code,
            block_description=block_desc_list,
        )
        results.append(
            {
                "module_id": module_id,
                "block_descriptions": [
                    {"code": v["code"], "description": v["description"]}
                    for v in block_dict.values()
                ],
                "signal_descriptions": signal_descriptions,
                "module_description": module_desc,
            }
        )

    save_descriptions_to_json(results)
    return


if __name__ == "__main__":
    repo_generation()

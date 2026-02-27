import sys

sys.path.append("../")
import graph_access.access as graph_utils
from utils import generate_response

block_requirements = """
You are a Verilog code documentation expert. You will be given a piece of Verilog code and a corresponding description written in natural language. Please evaluate the following English description based on these criteria:
1. Accuracy (0-5): Does it match the code's actual behavior?
2. Completeness (0-5): Are all key signals and logic branches covered?
3. Conciseness (0-5): Is it free of redundancy and within 50-150 words?
4. Terminology Consistency (0-5): Are technical terms and signal names correct?
5. Readability (0-5): Is the language clear and unambiguous?
Please provide a final overall score (0-10) and specific suggestions for improvement (which should be wrapped up by **).
"""

signal_requirements = """
You will be given a signal in a Verilog module and every code block containing this signal with description.
You need to summarize the functionality of this signal in natural language.
Write in 50-150 words.
"""

module_requirements = """
You will be given the description of every block and signal in a Verilog module.
You need to summarize the functionality of this module in natural language.
Write in 50-150 words.
"""


def blocks_grading():
    blocks = graph_utils.find_all_blocks()

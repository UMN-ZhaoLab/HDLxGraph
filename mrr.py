import os
import json
import numpy as np
from pathlib import Path
from typing import Dict, List, Tuple, Any, Optional, Union

class MRREvaluator:
    """使用MRR指标评估搜索结果"""

    def __init__(self, ground_truth: Dict[str, List[str]]):
        """
        初始化MRR评估器

        Args:
            ground_truth: 查询到相关块的映射，格式为 {query: [relevant_code_blocks]}
        """
        self.ground_truth = ground_truth

    def evaluate(self, query: str, results: List) -> float:
        """
        计算单个查询的倒数排名

        Args:
            query: 查询字符串
            results: 搜索结果列表

        Returns:
            倒数排名（如果没有相关结果则为0）
        """
        if query not in self.ground_truth:
            return 0.0

        relevant_blocks = self.ground_truth[query]

        # 打印调试信息
        print(f"\n调试 - 查询: {query[:50]}...")
        print(f"相关代码块数量: {len(relevant_blocks)}")
        if relevant_blocks:
            print(f"第一个相关代码块: {relevant_blocks[0][:100]}...")

        if results is None:
            results = []
        if isinstance(results, str):
            results = [results]

        for i, result in enumerate(results):
            if isinstance(result, dict):
                code = (
                    result.get("code")
                    or result.get("block_code")
                    or result.get("signal_name")
                    or ""
                )
            else:
                code = result
            # 检查代码是否匹配
            for relevant_code in relevant_blocks:
                # 使用更宽松的匹配方式，检查代码是否包含在相关代码中或相关代码是否包含在结果中
                if code in relevant_code or relevant_code in code:
                    print(f"找到匹配! 排名: {i+1}")
                    return 1.0 / (i + 1)

        return 0.0

    def evaluate_queries(self, queries: List[str], search_func) -> Dict[str, Any]:
        """
        评估多个查询的MRR

        Args:
            queries: 查询列表
            search_func: 搜索函数，接受查询字符串并返回结果列表

        Returns:
            包含MRR分数和每个查询倒数排名的字典
        """
        reciprocal_ranks = []
        query_results = {}

        for query in queries:
            results = search_func(query)
            rr = self.evaluate(query, results)
            reciprocal_ranks.append(rr)
            query_results[query] = rr

        mrr = np.mean(reciprocal_ranks) if reciprocal_ranks else 0.0

        return {
            'mrr': float(mrr),
            'query_results': query_results
        }


def load_processed_data(data_dir: str) -> Tuple[List[str], Dict[str, List[str]]]:
    """
    加载处理后的数据

    Args:
        data_dir: 数据目录

    Returns:
        查询列表和地面真相
    """
    # 加载查询
    with open(os.path.join(data_dir, 'test_queries.json'), 'r', encoding='utf-8') as f:
        test_queries = json.load(f)

    # 加载地面真相
    with open(os.path.join(data_dir, 'ground_truth.json'), 'r', encoding='utf-8') as f:
        ground_truth = json.load(f)

    return test_queries, ground_truth

def load_signal_data(data_dir: str) -> Tuple[List[str], Dict[str, List[str]]]:
    """
    加载处理后的数据

    Args:
        data_dir: 数据目录

    Returns:
        查询列表和地面真相
    """
    # 加载查询
    with open(os.path.join(data_dir, 'signal_queries.json'), 'r', encoding='utf-8') as f:
        test_queries = json.load(f)

    # 加载地面真相
    with open(os.path.join(data_dir, 'signal_ground_truth.json'), 'r', encoding='utf-8') as f:
        ground_truth = json.load(f)

    return test_queries, ground_truth

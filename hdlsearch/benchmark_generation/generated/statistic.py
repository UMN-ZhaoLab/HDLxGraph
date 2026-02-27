import json


def count_json_elements(file_path):
    with open(file_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return len(data)


# 使用示例
file_path = "./modules.json"
print(f"文件 {file_path} 中的 JSON 元素个数为: {count_json_elements(file_path)}")

import json

# 输入和输出文件路径
input_file = "modules.json"
output_file = "modules_unique.json"


def remove_duplicate_module_ids(input_file, output_file):
    seen_ids = set()
    result = []

    with open(input_file, "r", encoding="utf-8") as f:
        try:
            data = json.load(f)
        except json.JSONDecodeError as e:
            print(f"❌ 输入文件不是合法的 JSON: {e}")
            return

    for item in data:
        module_id = item.get("module_id")
        if module_id and module_id not in seen_ids:
            seen_ids.add(module_id)
            result.append(item)
        else:
            print(f"⚠️ 跳过重复的 module_id: {module_id}")

    # 写入去重后的结果
    with open(output_file, "w", encoding="utf-8") as out_f:
        json.dump(result, out_f, indent=2, ensure_ascii=False)

    print(f"✅ 去重完成，共保留 {len(result)} 条记录，结果已写入: {output_file}")


# 执行函数
if __name__ == "__main__":
    remove_duplicate_module_ids(input_file, output_file)

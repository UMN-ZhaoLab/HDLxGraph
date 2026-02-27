import json

import ijson

# 定义输入文件列表
input_files = [
    "aib-phy-hardware.json",
    "coffee.json",
    "e203.json",
    "FEC.json",
    "FPGA-SATA.json",
    "FPGA_IIC_EEPROM.json",
    "JPEG.json",
    "mips.json",
    "nontrivial-mips.json",
    "riffa.json",
    "verilog-axis.json",
    "verilog-ethernet.json",
]

# 定义输出文件路径
output_file = "modules.json"


def extract_module_descriptions_from_all(input_files, output_file):
    result = []

    for file_path in input_files:
        try:
            with open(file_path, "r", encoding="utf-8") as f:
                print(f"🔍 正在处理文件: {file_path}")
                modules = ijson.items(f, "item")

                for module in modules:
                    module_id = module.get("module_id")
                    module_desc = module.get("module_description")

                    if module_id and module_desc:
                        result.append(
                            {"module_id": module_id, "module_description": module_desc}
                        )

        except FileNotFoundError:
            print(f"❌ 文件不存在: {file_path}")
        except Exception as e:
            print(f"❌ 处理文件 {file_path} 时出错: {e}")

    # 写入 JSON 文件
    with open(output_file, "w", encoding="utf-8") as out_f:
        json.dump(result, out_f, indent=2, ensure_ascii=False)

    print(f"✅ 提取完成，共提取 {len(result)} 条描述，结果已写入: {output_file}")


# 执行函数
if __name__ == "__main__":
    extract_module_descriptions_from_all(input_files, output_file)

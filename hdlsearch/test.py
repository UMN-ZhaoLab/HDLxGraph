from rouge import Rouge


def test_rouge():
    rouge = Rouge()
    # 测试完全相同的代码
    code1 = "module adder(a, b, sum); input [3:0] a, b; output reg [4:0] sum; always @(a or b) sum = a + b; endmodule"
    code2 = "module adder(a, b, sum); input [3:0] a, b; output reg [4:0] sum; always @(a or b) sum = a + b; endmodule"
    score = rouge.get_scores(code1, code2)[0]["rouge-2"]["f"]
    print("Identical code ROUGE-2 F1:", score)  # 应接近 1.0

    # 测试有差异的代码
    code3 = "module adder(a, b, sum); input [3:0] a, b; output reg [4:0] sum; always @(a or b) sum = a + b + 1; endmodule"
    score = rouge.get_scores(code1, code3)[0]["rouge-2"]["f"]
    print("Different code ROUGE-2 F1:", score)  # 应明显低于 1.0


test_rouge()

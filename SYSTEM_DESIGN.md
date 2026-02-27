# Netlist2Neo4j 系统设计文档

## 概述

Netlist2Neo4j 是一个专门用于将硬件描述语言（HDL）网表文件转换为Neo4j图形数据库的工具。该系统主要处理Yosys综合工具生成的JSON格式网表，将复杂的电路连接关系转化为图形结构，便于进行电路分析、可视化和数据挖掘。

## 系统架构

### 1. 整体架构图

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Yosys JSON    │    │   Data Models   │    │   Neo4j Graph   │
│   Netlist File  │───▶│   & Parsers     │───▶│   Database      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                               │
                               ▼
                       ┌─────────────────┐
                       │   Import Logic  │
                       │   & Validation │
                       └─────────────────┘
```

### 2. 核心组件

#### 2.1 数据模型层 (Data Models)

**文件位置**: `netlist2neo4j/src/Json.scala`

**核心数据结构**:

```scala
// 信号表示：可以是整数或字符串
type Signal = Either[Int, String]
type Signals = List[Signal]

// Yosys根结构
case class YosysRoot(creator: String, modules: Map[String, YosysModule])

// 模块结构
case class YosysModule(
    attributes: Map[String, String],
    ports: Map[String, YosysPort],
    cells: Map[String, YosysCell],
    netnames: Map[String, YosysNetname]
)

// 端口结构
case class YosysPort(direction: String, bits: Signals)

// 单元结构
case class YosysCell(
    hide_name: Boolean,
    cell_type: String,
    parameters: Map[String, String],
    attributes: Map[String, String],
    port_directions: Map[String, String],
    connections: Map[String, List[Json]]
)

// 网络名称结构
case class YosysNetname(hide_name: Int, bits: Signals, attributes: Map[String, String])
```

#### 2.2 JSON解析层 (JSON Parsing)

**设计要点**:
- 使用Circe库进行JSON解析
- 自定义解码器处理Yosys特有的格式
- 支持混合类型信号（整数和字符串）

**关键解码器**:
- `signalDecoder`: 处理单个信号（整数或字符串）
- `signalsDecoder`: 处理信号数组（Yosys简单整数格式）
- `yosysPortDecoder`: 处理端口信息
- `yosysCellDecoder`: 处理逻辑单元
- `yosysModuleDecoder`: 处理模块结构

#### 2.3 数据库导入层 (Database Import)

**文件位置**: `netlist2neo4j/src/Neo4jImporter.scala`

**核心功能**:
- 建立Neo4j数据库连接
- 清理现有数据（可选）
- 批量导入模块、单元、网络和信号
- 创建节点和关系

**数据库模式**:
```
(Module)-[:CONTAINS]->(Cell)
(Module)-[:CONTAINS]->(Net)
(Cell)-[:HAS_PORT]->(Port)
(Net)-[:HAS_SIGNAL]->(Signal)
(Port)-[CONNECTION]->(Signal)
```

### 3. 数据流设计

#### 3.1 输入处理流程

```
Yosys JSON File → Json.scala解析器 → YosysRoot对象
    ↓
验证数据完整性 → 提取模块信息 → 准备数据库导入
```

#### 3.2 数据库导入流程

```
YosysRoot → 清理数据库 → 导入模块 → 导入网络和信号 → 导入单元和端口
    ↓
建立关系 → 生成统计信息 → 完成导入
```

### 4. 关键设计决策

#### 4.1 信号类型设计

**问题**: Yosys输出包含不同类型的信号值
- 整数信号：`[2, 3, 4]`
- 字符串信号：`["1", "0", "x"]`
- 混合信号：`[2, "1", "0"]`

**解决方案**: 使用`Either[Int, String]`类型
- `Left(Int)`：表示整数信号
- `Right(String)`：表示字符串信号

#### 4.2 JSON解析策略

**挑战**: Yosys JSON格式与标准Circe自动派生不兼容
- 简单数组格式：`[2, 3, 4]`
- 字段名差异：`type` vs `cell_type`
- 布尔值表示：整数`1`/`0` vs `true`/`false`

**解决方案**: 手动解码器
```scala
implicit val signalsDecoder: Decoder[Signals] = Decoder.instance { c =>
  c.as[List[Int]].map(_.map(Left(_))).orElse(
    c.as[List[String]].map(_.map(Right(_)))
  ).orElse(
    // 处理复杂情况...
  )
}
```

#### 4.3 数据库关系设计

**设计原则**:
- 每个硬件组件作为独立节点
- 使用关系表示连接和包含关系
- 支持复杂查询和图形遍历

**节点类型**:
- `Module`: 设计模块
- `Cell`: 逻辑单元
- `Net`: 网络/连线
- `Signal`: 信号值
- `Port`: 端口

### 5. 性能优化策略

#### 5.1 批量操作
- 使用Neo4j批量导入API
- 减少数据库往返次数
- 优化内存使用

#### 5.2 错误处理
- 详细的错误日志
- 优雅的错误恢复
- 数据验证机制

#### 5.3 内存管理
- 流式处理大型JSON文件
- 及时释放不再需要的资源
- 避免内存泄漏

### 6. 扩展性设计

#### 6.1 模块化架构
- 清晰的分层结构
- 易于添加新的数据类型
- 支持不同的JSON格式

#### 6.2 配置驱动
- 数据库连接参数可配置
- 支持不同的导入模式
- 可扩展的验证规则

### 7. 测试策略

#### 7.1 单元测试
- JSON解析正确性
- 数据模型验证
- 数据库操作测试

#### 7.2 集成测试
- 端到端工作流测试
- 真实Yosys网表测试
- 性能基准测试

#### 7.3 错误处理测试
- 异常输入处理
- 数据库连接失败处理
- 内存不足情况处理

## 使用示例

### 基本用法
```bash
# 导入网表文件
mill netlist2neo4j.run path/to/netlist.json

# 清理并重新导入
mill netlist2neo4j.run path/to/netlist.json --clear
```

### 编程接口
```scala
// 解析JSON
val yosysRoot = parseJsonFile(jsonPath)

// 导入数据库
val importer = Neo4jImporter(uri, username, password)
importer.importNetlist(yosysRoot, clearExisting = true)
```

## 技术栈

- **语言**: Scala 3
- **JSON处理**: Circe
- **数据库**: Neo4j
- **构建工具**: Mill
- **日志**: SLF4J + Logback
- **测试**: ScalaTest

## 未来扩展方向

1. **支持更多EDA工具**: Cadence、Synopsys等
2. **高级分析功能**: 时序分析、功耗分析
3. **可视化界面**: 集成图形化查看器
4. **性能优化**: 支持更大规模的设计
5. **云原生支持**: 容器化部署

---

这个设计文档详细描述了Netlist2Neo4j系统的整体架构、核心组件、设计决策和扩展性考虑。系统采用了现代化的Scala技术栈，具有良好的模块化设计和扩展性。
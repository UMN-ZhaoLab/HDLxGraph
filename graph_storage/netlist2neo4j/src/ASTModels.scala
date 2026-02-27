package netlist2neo4j

/**
 * 源代码位置信息
 */
case class SourcePosition(
  file: Option[String],
  line: Option[Int],
  column: Option[Int]
)

/**
 * 源代码范围信息（用于Module和Block的完整代码提取）
 */
case class SourceRange(
  file: String,
  startLine: Int,
  endLine: Int
)

/**
 * AST节点基础属性
 */
case class ASTNodeAttributes(
  memoryAddress: Option[String],
  sourcePosition: SourcePosition,
  flags: Set[String]
)

// ============================================================================
// 简化的AST节点类型 - 参考 HDLxGraph
// ============================================================================

/**
 * AST节点类型枚举（简化版）
 */
sealed trait ASTNodeType
case object ASTModule extends ASTNodeType
case object ASTBlock extends ASTNodeType
case object ASTSignal extends ASTNodeType

/**
 * 抽象基类：所有AST节点的基础
 */
sealed trait ASTNode {
  def nodeType: ASTNodeType
  def attributes: ASTNodeAttributes
}

/**
 * Module节点 - 对应模块、函数、任务等容器结构
 */
case class ASTModuleNode(
  name: String,
  attributes: ASTNodeAttributes,
  sourceRange: Option[SourceRange],
  ports: List[ASTSignalNode],
  parameters: List[ASTParameter],
  internalSignals: List[ASTSignalNode],
  subModules: List[ASTModuleNode],
  blocks: List[ASTBlockNode],
  instantiations: List[ASTInstantiation]
) extends ASTNode {
  def nodeType: ASTNodeType = ASTModule
}

/**
 * Block节点 - 对应always、initial、function、task中的代码块
 */
case class ASTBlockNode(
  name: Option[String], // 可选的块名称
  blockType: String,    // "always", "initial", "function", "task", "if", "case", "for", etc.
  instanceName: Option[String] = None, // 对于cell块，记录例化名
  attributes: ASTNodeAttributes,
  sourceRange: Option[SourceRange],
  statements: List[ASTStatement],
  sensitivityList: Option[List[ASTSignalNode]] = None, // for always blocks
  ports: List[ASTSignalNode] = Nil, // for function/task blocks
  referencedSignals: List[String] = Nil // 该块内引用的信号名
) extends ASTNode {
  def nodeType: ASTNodeType = ASTBlock
}

/**
 * Signal节点 - 对应信号、线、变量、参数等
 */
case class ASTSignalNode(
  name: String,
  signalType: String,  // "wire", "reg", "input", "output", "inout", "parameter", etc.
  width: Option[Int],
  signed: Boolean,
  attributes: ASTNodeAttributes,
  initialValue: Option[String] = None
) extends ASTNode {
  def nodeType: ASTNodeType = ASTSignal
}

/**
 * 参数定义
 */
case class ASTParameter(
  name: String,
  value: String,
  attributes: ASTNodeAttributes
)

/**
 * 模块实例化
 */
case class ASTInstantiation(
  instanceName: String,
  moduleName: String,
  connections: List[ASTConnection],
  attributes: ASTNodeAttributes
)

/**
 * 连接关系
 */
case class ASTConnection(
  portName: String,
  signalName: String
)

/**
 * 语句类型（用于Block内部）
 */
sealed trait ASTStatement

/**
 * 赋值语句
 */
case class ASTAssignmentStatement(
  leftSignal: String,
  rightExpression: String,
  isBlocking: Boolean,
  attributes: ASTNodeAttributes
) extends ASTStatement

/**
 * 条件语句
 */
case class ASTConditionalStatement(
  condition: String,
  trueStatements: List[ASTStatement],
  falseStatements: List[ASTStatement],
  attributes: ASTNodeAttributes
) extends ASTStatement

/**
 * Case语句
 */
case class ASTCaseStatement(
  expression: String,
  cases: List[ASTCaseItem],
  attributes: ASTNodeAttributes
) extends ASTStatement

/**
 * Case项
 */
case class ASTCaseItem(
  expressions: List[String],
  statements: List[ASTStatement],
  isDefault: Boolean = false,
  attributes: ASTNodeAttributes
)

/**
 * 循环语句
 */
case class ASTLoopStatement(
  loopType: String, // "for", "while", "repeat", "forever"
  condition: Option[String],
  initialization: Option[String],
  update: Option[String],
  statements: List[ASTStatement],
  attributes: ASTNodeAttributes
) extends ASTStatement

/**
 * 函数/任务调用
 */
case class ASTCallStatement(
  functionName: String,
  arguments: List[String],
  attributes: ASTNodeAttributes
) extends ASTStatement

// ============================================================================
// AST图的边类型 - 参考 HDLxGraph
// ============================================================================

/**
 * AST边类型枚举
 */
sealed trait ASTEdgeType
case object CONTAIN extends ASTEdgeType   // 包含关系
case object INSTANTIATE extends ASTEdgeType // 实例化关系

/**
 * AST边基类
 */
sealed trait ASTEdge {
  def edgeType: ASTEdgeType
  def fromNodeId: String
  def toNodeId: String
  def attributes: ASTNodeAttributes
}

/**
 * CONTAIN边 - 一个节点包含另一个节点
 */
case class ASTContainEdge(
  fromNodeId: String,
  toNodeId: String,
  attributes: ASTNodeAttributes,
  containType: String // "has_port", "has_signal", "has_block", "has_parameter", etc.
) extends ASTEdge {
  def edgeType: ASTEdgeType = CONTAIN
}

/**
 * INSTANTIATE边 - 一个模块实例化另一个模块
 */
case class ASTInstantiateEdge(
  fromNodeId: String,    // 实例化者的Module ID
  toNodeId: String,      // 被实例化者的Module ID
  instanceName: String,  // 实例名称
  connections: List[ASTConnection], // 端口连接映射
  attributes: ASTNodeAttributes
) extends ASTEdge {
  def edgeType: ASTEdgeType = INSTANTIATE
}

/**
 * AST图结构
 */
case class ASTGraph(
  nodes: List[ASTNode],
  edges: List[ASTEdge]
)

/**
 * AST根节点（包含整个文件的内容）
 */
case class ASTRoot(
  modules: List[ASTModuleNode],
  graph: ASTGraph
)

/**
 * AST解析结果
 */
case class ASTParseResult(
  root: ASTRoot,
  parseTime: Long,
  nodeCount: Int,
  edgeCount: Int
)

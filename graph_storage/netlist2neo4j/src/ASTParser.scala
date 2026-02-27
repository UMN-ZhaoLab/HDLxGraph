package netlist2neo4j

import com.typesafe.scalalogging.Logger
import scala.util.{Try, Success, Failure}
import scala.util.matching.Regex
import java.io.File
import scala.io.Source

/**
 * AST解析器 - 将Yosys生成的AST文本文件转换为数据模型
 */
object ASTParser {
  private val logger = Logger("ast-parser")

  // AST行匹配模式
  private val astNodePattern = """\s*(?:\d+:)?\s*(AST_\w+)\s+(.*)""".r
  private val attributePattern = """\s+(\w+):?\s+(.*)""".r
  private val sourceRangePattern = """<([^:]+):(\d+)\.(\d+)-(\d+)\.(\d+)>""".r
  private val positionPattern = """(\d+)\.(\d+)-(\d+)\.(\d+)""".r
  private val numberPattern = """(\d+)'([sdh])?([0-9a-fA-F_]+)""".r

  /**
   * 解析AST文件
   */
  def parseASTFile(filePath: String): Try[ASTParseResult] = {
    val startTime = System.currentTimeMillis()

    Try {
      val file = new File(filePath)
      if (!file.exists()) {
        throw new Exception(s"AST file not found: $filePath")
      }

      val lines = Source.fromFile(file)("UTF-8").getLines().toList
      val (root, nodeCount, edgeCount) = parseASTLines(lines)

      val parseTime = System.currentTimeMillis() - startTime
      logger.info(s"AST parsing completed in ${parseTime}ms: $nodeCount nodes, $edgeCount edges")

      ASTParseResult(root, parseTime, nodeCount, edgeCount)
    }.recoverWith {
      case ex: Exception =>
        logger.error(s"Failed to parse AST file: $filePath", ex)
        Failure(ex)
    }
  }

  /**
   * 解析AST行内容
   */
  private def parseASTLines(lines: List[String]): (ASTRoot, Int, Int) = {
    var moduleOccurrences = scala.collection.mutable.Map[String, List[(String, Iterator[String])]]()

    // 收集所有模块出现
    val linesWithIndex = lines.zipWithIndex
    linesWithIndex.foreach { case (line, index) =>
      if (line.contains("AST_MODULE")) {
        val (name, _) = parseASTNodeLine(line)
        val remainingLines = lines.drop(index + 1).iterator
        moduleOccurrences(name) = (line, remainingLines) :: moduleOccurrences.getOrElse(name, List.empty)
      }
    }

    var modules = List.empty[ASTModuleNode]
    var allNodes = List.empty[ASTNode]
    var allEdges = List.empty[ASTEdge]
    var allNodeIds = Set.empty[String]
    var nodeCounter = 0

    // 只处理每个模块的最后一次出现
    moduleOccurrences.foreach { case (moduleName, occurrences) =>
      if (occurrences.nonEmpty) {
        val (lastModuleLine, lastModuleIter) = occurrences.last
        val (module, nodes, edges, nodeIds) = parseModule(lastModuleLine, lastModuleIter.buffered)
        modules :+= module
        allNodes ++= nodes
        allEdges ++= edges
        allNodeIds ++= nodeIds
        nodeCounter += nodes.length + 1 // +1 for module itself

        logger.debug(s"Processed module '$moduleName' (total occurrences: ${occurrences.length})")
      }
    }

    val moduleIdByName: Map[String, String] =
      modules.map(m => m.name -> ASTId.moduleId(m)).toMap

    // 确保所有模块ID都在集合中，以便实例化边不过滤掉目标模块
    allNodeIds ++= moduleIdByName.values

    val resolvedEdges = allEdges.flatMap {
      case i: ASTInstantiateEdge if ASTId.isModuleRef(i.toNodeId) =>
        val refName = ASTId.moduleRefName(i.toNodeId)
        moduleIdByName.get(refName).map(id => i.copy(toNodeId = id))
      case other => Some(other)
    }

    // 去重边，按 edgeType+from+to+特定属性
    val dedupEdges = resolvedEdges
      .groupBy {
        case c: ASTContainEdge     => (c.edgeType, c.fromNodeId, c.toNodeId, c.containType)
        case i: ASTInstantiateEdge => (i.edgeType, i.fromNodeId, i.toNodeId, i.instanceName)
      }
      .values
      .map(_.head)
      .toList

    // 过滤掉未创建节点的边
    val validEdges = dedupEdges.filter(e => allNodeIds.contains(e.fromNodeId) && allNodeIds.contains(e.toNodeId))

    val graph = ASTGraph(allNodes, validEdges)
    val root = ASTRoot(modules, graph)

    (root, nodeCounter, validEdges.length)
  }

  /**
   * 解析模块定义
   */
  private def parseModule(moduleLine: String, lineIter: BufferedIterator[String]): (ASTModuleNode, List[ASTNode], List[ASTEdge], Set[String]) = {
    val (name, attributes) = parseASTNodeLine(moduleLine)

    // 提取Module的SourceRange信息
    val sourceRange = extractSourceRange(moduleLine)
    val moduleFile = sourceRange.map(_.file)
    val moduleAttributes = attributes.copy(sourcePosition = attributes.sourcePosition.copy(file = moduleFile))
    val moduleId = ASTId.moduleId(name, moduleFile, moduleAttributes.memoryAddress)
    def portId(portName: String) = ASTId.portId(moduleId, portName)
    def signalId(signalName: String) = ASTId.signalId(moduleId, signalName)
    def blockId(blockName: String) = ASTId.blockId(moduleId, blockName)

    var ports = List.empty[ASTSignalNode]
    var parameters = List.empty[ASTParameter]
    var internalSignals = List.empty[ASTSignalNode]
    var blocks = List.empty[ASTBlockNode]
    var instantiations = List.empty[ASTInstantiation]
    var subModules = List.empty[ASTModuleNode]

    var moduleNodes = List.empty[ASTNode]
    var moduleEdges = List.empty[ASTEdge]
    val moduleNodeIds = scala.collection.mutable.Set[String](moduleId)

    // 声明块：用于承载端口/信号的包含关系，避免直接挂在模块上
    val declBlockName = "declarations"
    val declBlockId = blockId(declBlockName)
    val declBlock = ASTBlockNode(
      name = Some(declBlockName),
      blockType = "declaration",
      attributes = moduleAttributes,
      sourceRange = sourceRange,
      statements = Nil,
      sensitivityList = None,
      ports = Nil
    )
    blocks :+= declBlock
    moduleNodeIds += declBlockId
    moduleEdges :+= ASTContainEdge(
      fromNodeId = moduleId,
      toNodeId = declBlockId,
      attributes = moduleAttributes,
      containType = "has_block"
    )

    var currentIndent = 0
    var inModule = true

    while (lineIter.hasNext && inModule) {
      val line = lineIter.next()
      if (line.trim.isEmpty) {
        // skip empty lines
      } else if (line.trim.contains("AST_MODULE") && line.trim.startsWith(" ")) {
        // Nested module
        val (subModule, nodes, edges, nodeIds) = parseModule(line, lineIter)
        subModules :+= subModule
        moduleNodes ++= nodes
        moduleEdges ++= edges
        moduleNodeIds ++= nodeIds

        // Add instantiations edge
        val instantiation = ASTInstantiation(
          instanceName = s"inst_${subModule.name}",
          moduleName = subModule.name,
          connections = List.empty, // Will be filled later when connections are parsed
          attributes = subModule.attributes
        )
        instantiations :+= instantiation

        val edge = ASTInstantiateEdge(
          fromNodeId = moduleId,
          toNodeId = ASTId.moduleId(subModule),
          instanceName = instantiation.instanceName,
          connections = instantiation.connections,
          attributes = subModule.attributes
        )
        moduleEdges :+= edge
      } else if (!line.startsWith(" ")) {
        // End of current module
        inModule = false
      } else {
        // Parse module content
        if (line.contains("AST_PARAMETER") || line.contains("AST_LOCALPARAM")) {
          val (param, paramNode, paramEdges) = parseParameter(line, lineIter, moduleId, moduleFile)
          parameters :+= param
          moduleNodes :+= paramNode
          moduleEdges ++= paramEdges
        } else if (line.contains("AST_WIRE") || line.contains("AST_MEMORY") ||
                   line.contains("AST_PORT") || line.contains("AST_ARGUMENT")) {
        val (signal, signalNode, signalEdges) = parseSignal(line, lineIter, moduleId, moduleFile, portId, signalId)
        if (line.contains("AST_PORT")) {
          ports :+= signal
        } else {
          internalSignals :+= signal
        }
        moduleNodes :+= signalNode
      } else if (line.contains("AST_CELL")) {
        val (instantiation, _) = parseCellInstantiation(line, lineIter, moduleId, moduleFile)
        instantiations :+= instantiation
        // 将 AST_CELL 视为一个 block
        val cellBlockName = s"cell_${instantiation.instanceName}_${instantiation.attributes.sourcePosition.line.getOrElse(0)}"
        val cellBlockId = blockId(cellBlockName)
        val cellBlock = ASTBlockNode(
          name = Some(cellBlockName),
          blockType = "cell",
          instanceName = Some(instantiation.instanceName),
          attributes = instantiation.attributes,
          sourceRange = None,
          statements = Nil,
          sensitivityList = None,
          ports = Nil
        )
        blocks :+= cellBlock
        moduleNodeIds += cellBlockId
        moduleEdges :+= ASTContainEdge(
          fromNodeId = moduleId,
          toNodeId = cellBlockId,
          attributes = instantiation.attributes,
          containType = "has_block"
        )
        // block -> 被实例化模块
        moduleEdges :+= ASTInstantiateEdge(
          fromNodeId = cellBlockId,
          toNodeId = ASTId.moduleRefId(instantiation.moduleName),
          instanceName = instantiation.instanceName,
          connections = instantiation.connections,
          attributes = instantiation.attributes
        )
      } else if (line.contains("AST_ALWAYS") || line.contains("AST_INITIAL") ||
                   line.contains("AST_FUNCTION") || line.contains("AST_TASK")) {
          val blockIndent = countIndent(line)
          val (block, blockNode, blockEdges, _) = parseBlock(line, lineIter, moduleId, moduleFile, blockId, blockIndent, ports.map(_.name).toSet, internalSignals.map(_.name).toSet)
          blocks :+= block
          moduleNodes :+= blockNode
          moduleEdges ++= blockEdges
          blockEdges.foreach(e => moduleNodeIds += e.toNodeId)
        } else if (line.contains("AST_ASSIGN") || line.contains("AST_ASSIGN_EQ")) {
          // Assignment statements are handled within blocks
        }
      }
    }

    // 去重（按名称）避免同名信号/端口重复计数，同时收集ID
    def dedupSignals(signals: List[ASTSignalNode]): (List[ASTSignalNode], Set[String]) = {
      val deduped = signals.groupBy(_.name).values.map(_.head).toList
      val ids = deduped.map { s =>
        if (s.signalType == "input" || s.signalType == "output" || s.signalType == "inout" || s.signalType == "port")
          portId(s.name)
        else signalId(s.name)
      }.toSet
      (deduped, ids)
    }

    val (dedupPorts, portIds) = dedupSignals(ports)
    val (dedupSignalsList, signalIds) = dedupSignals(internalSignals)
    val portNameSet = dedupPorts.map(_.name).toSet
    val signalNameSet = dedupSignalsList.map(_.name).toSet
    def resolveSignalId(name: String): String = {
      if (portNameSet.contains(name)) portId(name) else signalId(name)
    }

    val module = ASTModuleNode(
      name = name,
      attributes = moduleAttributes,
      sourceRange = sourceRange,
      ports = dedupPorts,
      parameters = parameters,
      internalSignals = dedupSignalsList,
      subModules = subModules,
      blocks = blocks,
      instantiations = instantiations
    )

    moduleNodes :+= module
    moduleNodeIds ++= portIds
    moduleNodeIds ++= signalIds
    moduleNodeIds += moduleId
    // Block -> Signal edges based on referencedSignals
    blocks.foreach { block =>
      val blockDisplayName = block.name.getOrElse(s"${block.blockType}_${block.sourceRange.flatMap(sr => Some(sr.startLine)).getOrElse(0)}")
      val blockNodeId = blockId(blockDisplayName)
      moduleNodeIds += blockNodeId
      block.referencedSignals
        .filter(sigName => portNameSet.contains(sigName) || signalNameSet.contains(sigName))
        .distinct
        .foreach { sigName =>
          val toId = resolveSignalId(sigName)
          moduleEdges :+= ASTContainEdge(
            fromNodeId = blockNodeId,
            toNodeId = toId,
            attributes = block.attributes,
            containType = "uses_signal"
          )
          moduleNodeIds += toId
        }
    }
    // 声明块 -> 端口/信号
    dedupPorts.foreach { port =>
      val pid = portId(port.name)
      moduleNodeIds += pid
      moduleEdges :+= ASTContainEdge(
        fromNodeId = declBlockId,
        toNodeId = pid,
        attributes = port.attributes,
        containType = "has_port"
      )
    }

    dedupSignalsList.foreach { signal =>
      val sid = signalId(signal.name)
      moduleNodeIds += sid
      moduleEdges :+= ASTContainEdge(
        fromNodeId = declBlockId,
        toNodeId = sid,
        attributes = signal.attributes,
        containType = s"has_${signal.signalType}"
      )
    }

    (module, moduleNodes, moduleEdges, moduleNodeIds.toSet)
  }

  /**
   * 解析参数
   */
  private def parseParameter(line: String, lineIter: Iterator[String], parentId: String, parentFile: Option[String]): (ASTParameter, ASTSignalNode, List[ASTEdge]) = {
    val (name, baseAttributes) = parseASTNodeLine(line)
    val attributes = baseAttributes.copy(
      sourcePosition = baseAttributes.sourcePosition.copy(file = parentFile)
    )
    val isLocal = line.contains("AST_LOCALPARAM")

    // Look for value in next lines
    var value = ""
    var currentValueLine = lineIter.next()
    while (currentValueLine.trim.isEmpty) {
      currentValueLine = lineIter.next()
    }

    if (currentValueLine.contains("AST_CONSTANT")) {
      value = extractConstantValue(currentValueLine)
    } else {
      value = "undefined"
    }

    val param = ASTParameter(
      name = name,
      value = value,
      attributes = attributes
    )

    val signalNode = ASTSignalNode(
      name = name,
      signalType = if (isLocal) "localparam" else "parameter",
      width = None,
      signed = false,
      attributes = attributes
    )

    val edges = List(ASTContainEdge(
      fromNodeId = parentId,
      toNodeId = s"ast_param_$name",
      attributes = attributes,
      containType = "has_parameter"
    ))

    (param, signalNode, edges)
  }

  /**
   * 解析信号
   */
  private def parseSignal(
    line: String,
    lineIter: Iterator[String],
    parentId: String,
    parentFile: Option[String],
    portIdFn: String => String,
    signalIdFn: String => String
  ): (ASTSignalNode, ASTSignalNode, List[ASTEdge]) = {
    val (name, baseAttributes) = parseASTNodeLine(line)
    val attributes = baseAttributes.copy(
      sourcePosition = baseAttributes.sourcePosition.copy(file = parentFile)
    )

    // Determine signal type
    val signalType = if (line.contains("AST_PORT")) {
      if (line.contains("input")) "input"
      else if (line.contains("output")) "output"
      else if (line.contains("inout")) "inout"
      else "port"
    } else if (line.contains("AST_WIRE")) {
      "wire"
    } else if (line.contains("AST_MEMORY")) {
      "memory"
    } else {
      "signal"
    }

    // Parse width and signed flags from attributes
    var width: Option[Int] = None
    var signed = false

    if (attributes.sourcePosition.line.nonEmpty) {
      // This is a simplified parsing - in practice, we'd need to parse more complex width specifications
      val widthMatch = """width:\s*(\d+)""".r.findFirstMatchIn(attributes.flags.mkString(" "))
      width = widthMatch.map(_.group(1).toInt)
      signed = attributes.flags.contains("signed")
    }

    val signalNode = ASTSignalNode(
      name = name,
      signalType = signalType,
      width = width,
      signed = signed,
      attributes = attributes
    )

    val signalId =
      if (signalType == "input" || signalType == "output" || signalType == "inout" || signalType == "port")
        portIdFn(name)
      else
        signalIdFn(name)
    val edges = List.empty[ASTEdge]

    (signalNode, signalNode, edges)
  }

  /**
   * 解析单元实例化
   */
  private def parseCellInstantiation(line: String, lineIter: Iterator[String], parentId: String, parentFile: Option[String]): (ASTInstantiation, ASTSignalNode) = {
    val (name, baseAttributes) = parseASTNodeLine(line)
    val attributes = baseAttributes.copy(
      sourcePosition = baseAttributes.sourcePosition.copy(file = parentFile)
    )

    // Parse cell type
    var cellType = ""
    var currentLine = lineIter.next()
    while (currentLine.trim.isEmpty) {
      currentLine = lineIter.next()
    }

    if (currentLine.contains("AST_CELLTYPE")) {
      val (_, typeAttributes) = parseASTNodeLine(currentLine)
      cellType = extractTypeName(currentLine).trim
    }

    val instantiation = ASTInstantiation(
      instanceName = name,
      moduleName = cellType,
      connections = List.empty, // TODO: parse port connections
      attributes = attributes
    )

    // Create a signal node to represent the instantiation
    val signalNode = ASTSignalNode(
      name = name,
      signalType = "instance",
      width = None,
      signed = false,
      attributes = attributes
    )

    (instantiation, signalNode)
  }

  /**
   * 解析代码块
   */
  private def parseBlock(
    line: String,
    lineIter: BufferedIterator[String],
    parentId: String,
    parentFile: Option[String],
    blockIdFn: String => String,
    blockIndent: Int,
    portNames: Set[String],
    signalNames: Set[String]
  ): (ASTBlockNode, ASTBlockNode, List[ASTEdge], Int) = {
    val (name, baseAttributes) = parseASTNodeLine(line)

    // 提取Block的SourceRange信息
    val sourceRange = extractSourceRange(line)
    val blockFile = sourceRange.map(_.file).orElse(parentFile)
    val attributes = baseAttributes.copy(
      sourcePosition = baseAttributes.sourcePosition.copy(
        file = blockFile,
        line = sourceRange.map(_.startLine)
      )
    )

    // Determine block type
    val blockType = if (line.contains("AST_ALWAYS")) {
      "always"
    } else if (line.contains("AST_INITIAL")) {
      "initial"
    } else if (line.contains("AST_FUNCTION")) {
      "function"
    } else if (line.contains("AST_TASK")) {
      "task"
    } else {
      "block"
    }

    // Parse statements within the block
    var statements = List.empty[ASTStatement]
    var sensitivityList = Option.empty[List[ASTSignalNode]]

    // Skip block content parsing for now - this would need more sophisticated parsing
    // In a complete implementation, we'd parse all statements recursively

    val blockDisplayName = if (name.nonEmpty) name else s"${blockType}_${sourceRange.map(_.startLine).getOrElse(0)}"
    val blockId = blockIdFn(blockDisplayName)
    // 扫描块内的标识符引用
    var referencedSignals = List.empty[String]
    var consumed = 0
    while (lineIter.hasNext && countIndent(lineIter.head) > blockIndent) {
      val innerLine = lineIter.next()
      consumed += 1
      if (innerLine.contains("AST_IDENTIFIER")) {
        val (idName, _) = parseASTNodeLine(innerLine)
        referencedSignals :+= idName
      }
    }

    val filteredRefs = referencedSignals.distinct.filter(name => portNames.contains(name) || signalNames.contains(name))

    val blockNode = ASTBlockNode(
      name = Some(blockDisplayName),
      blockType = blockType,
      attributes = attributes,
      sourceRange = sourceRange,
      statements = statements,
      sensitivityList = sensitivityList,
      ports = List.empty,
      referencedSignals = filteredRefs
    )

    val edges = List(ASTContainEdge(
      fromNodeId = parentId,
      toNodeId = blockId,
      attributes = attributes,
      containType = "has_block"
    ))

    (blockNode, blockNode, edges, consumed)
  }

  /**
   * 解析AST节点行
   */
  private def parseASTNodeLine(line: String): (String, ASTNodeAttributes) = {
    val trimmedLine = line.trim

    // Special handling for AST_MODULE lines
    // Extract name via str='...'
    def extractName(text: String): Option[String] = {
      val strPattern = """.*str='([^']+)'.*""".r
      val dblPattern = """.*\"([^\"]+)\".*""".r
      text match
        case strPattern(n) => Some(n)
        case dblPattern(n) => Some(n)
        case _ => None
    }

    if (trimmedLine.contains("AST_MODULE")) {
      val name = extractName(trimmedLine).getOrElse("unknown")
      (name, extractAttributes(trimmedLine))
    } else {
      trimmedLine match {
        case astNodePattern(nodeType, remainder) =>
          val name = extractName(remainder).getOrElse {
            remainder.split("\\s+").headOption.getOrElse("unknown")
          }
          (name, extractAttributes(remainder))
        case _ =>
          ("unknown", extractAttributes(trimmedLine))
      }
    }
  }

  /**
   * 提取SourceRange信息（完整源代码范围）
   */
  private def extractSourceRange(line: String): Option[SourceRange] = {
    sourceRangePattern.findFirstMatchIn(line).map { m =>
      val file = m.group(1)
      val startLine = m.group(2).toInt
      val startCol = m.group(3).toInt
      val endLine = m.group(4).toInt
      val endCol = m.group(5).toInt

      SourceRange(
        file = if (file.contains("/")) file else file, // 保持完整路径
        startLine = startLine,
        endLine = endLine
      )
    }
  }

  /**
   * 提取行属性
   */
  private def extractAttributes(remainder: String): ASTNodeAttributes = {
    var memoryAddress: Option[String] = None
    var sourcePosition = SourcePosition(None, None, None)
    var flags = Set.empty[String]

    // Extract memory address
    val addrPattern = """0x[0-9a-fA-F]+""".r
    memoryAddress = addrPattern.findFirstIn(remainder)

    // Extract source position
    val posMatch = positionPattern.findFirstMatchIn(remainder)
    posMatch.foreach { m =>
      sourcePosition = SourcePosition(
        file = None, // File info would need to be extracted from parent context
        line = Some(m.group(1).toInt),
        column = Some(m.group(2).toInt)
      )
    }

    // Extract flags
    if (remainder.contains("signed")) flags += "signed"
    if (remainder.contains("signed")) flags += "signed"
    if (remainder.contains("input")) flags += "input"
    if (remainder.contains("output")) flags += "output"
    if (remainder.contains("inout")) flags += "inout"

    ASTNodeAttributes(memoryAddress, sourcePosition, flags)
  }

  /**
   * 提取常量值
   */
  private def extractConstantValue(line: String): String = {
    if (line.contains("\"")) {
      // String constant
      val stringPattern = """\"([^\"]+)\"""".r
      stringPattern.findFirstMatchIn(line).map(_.group(1)).getOrElse("")
    } else {
      // Numeric constant
      val numberPattern = """(\d+)'?([sdh])?([0-9a-fA-F_]+)""".r
      numberPattern.findFirstMatchIn(line) match {
        case Some(m) =>
          s"${m.group(1)}'${Option(m.group(2)).getOrElse("")}${Option(m.group(3)).getOrElse("")}"
        case None => ""
      }
    }
  }

  /**
   * 提取类型名称
   */
  private def extractTypeName(line: String): String = {
    // 优先匹配 str='...' 或 "..."
    val strPattern = """.*str='([^']+)'.*""".r
    val dblPattern = """.*\"([^\"]+)\".*""".r
    line match
      case strPattern(n) => n
      case dblPattern(n) => n
      case _ =>
        line.split("\\s+").drop(2).headOption.getOrElse("unknown")
  }

  /**
   * 计算行首缩进（空格数）
   */
  private def countIndent(line: String): Int = {
    line.takeWhile(_ == ' ').length
  }
}

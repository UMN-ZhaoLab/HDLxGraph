package netlist2neo4j

import com.typesafe.scalalogging.Logger
import org.neo4j.driver.{Driver, Session, TransactionContext, Values}
import io.circe.Json
import scala.jdk.CollectionConverters.*
import scala.util.{Using, Success, Failure, Try}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.io.Source
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

case class NetlistImportStats(
  modules: Int,
  cells: Int,
  nets: Int,
  signals: Int,
  ports: Int
)

case class ASTImportStats(
  modules: Int,
  blocks: Int,
  signals: Int,
  edges: Int
)

case class DFGImportStats(
  nodes: Int,
  edges: Int,
  signalRefs: Int,
  blockAnchors: Int
)

class Neo4jImporter(
    driver: Driver,
    embeddingConfig: Option[EmbeddingService.EmbeddingConfig] = None,
    batchSize: Int = 200,
    parallelModules: Boolean = false,
    generateEmbeddings: Boolean = false
) {
  val logger = Logger("netlist2neo4j-importer")
  private val safeBatchSize = math.max(1, batchSize)
  // Netlist批量写入分组，控制在较小范围避免长时间锁持有
  private val netBatchSize = math.min(200, safeBatchSize)

  def clearDatabase(): (Long, Long) = {
    val result: Try[(Long, Long)] = Using(resource = driver.session()) { session =>
      val beforeCount = session.executeRead { tx =>
        tx.run("MATCH (n) RETURN count(n) AS cnt").single().get("cnt").asLong()
      }
      session.executeWrite { tx =>
        tx.run("MATCH (n) DETACH DELETE n").consume()
      }
      val afterCount = session.executeRead { tx =>
        tx.run("MATCH (n) RETURN count(n) AS cnt").single().get("cnt").asLong()
      }
      (beforeCount, afterCount)
    }

    result match
      case Success((before, after)) =>
        logger.info(s"Database cleared: before=$before nodes, after=$after nodes")
        if (after > 0) {
          logger.warn(s"Database clear incomplete: $after nodes remain")
        }
        (before, after)
      case Failure(ex) =>
        logger.error(s"Failed to clear database: ${ex.getMessage}", ex)
        throw ex
  }

  /**
   * 为常用匹配字段建立索引/约束，减少导入开销。
   * 在清库后或导入前调用。
   */
  def ensureIndexes(): Unit = {
    Using(driver.session()) { session =>
      session.executeWrite { tx =>
        tx.run("CREATE CONSTRAINT module_name_unique IF NOT EXISTS FOR (m:Module) REQUIRE m.name IS UNIQUE")
        tx.run("CREATE CONSTRAINT cell_id_unique IF NOT EXISTS FOR (c:Cell) REQUIRE c.id IS UNIQUE")
        tx.run("CREATE CONSTRAINT port_id_unique IF NOT EXISTS FOR (p:Port) REQUIRE p.id IS UNIQUE")
        tx.run("CREATE CONSTRAINT net_nodekey IF NOT EXISTS FOR (n:Net) REQUIRE (n.module, n.name) IS NODE KEY")
        tx.run("CREATE CONSTRAINT signal_nodekey IF NOT EXISTS FOR (s:Signal) REQUIRE (s.module, s.id) IS NODE KEY")
        tx.run("CREATE CONSTRAINT ast_module_id_unique IF NOT EXISTS FOR (am:ASTModule) REQUIRE am.id IS UNIQUE")
        tx.run("CREATE CONSTRAINT ast_signal_id_unique IF NOT EXISTS FOR (asig:ASTSignal) REQUIRE asig.id IS UNIQUE")
        tx.run("CREATE CONSTRAINT ast_block_id_unique IF NOT EXISTS FOR (ab:ASTBlock) REQUIRE ab.id IS UNIQUE")
        tx.run("CREATE CONSTRAINT dfg_node_id_unique IF NOT EXISTS FOR (d:DFGNode) REQUIRE d.id IS UNIQUE")
        // 常用匹配字段索引
        tx.run("CREATE INDEX ast_block_blocktype IF NOT EXISTS FOR (ab:ASTBlock) ON (ab.blockType)")
        tx.run("CREATE INDEX ast_signal_module IF NOT EXISTS FOR (asig:ASTSignal) ON (asig.module)")
        tx.run("CREATE INDEX ast_module_name IF NOT EXISTS FOR (am:ASTModule) ON (am.name)")
        tx.run("CREATE INDEX dfg_node_name IF NOT EXISTS FOR (d:DFGNode) ON (d.name)")
        tx.run("CREATE INDEX dfg_node_module IF NOT EXISTS FOR (d:DFGNode) ON (d.module)")
        // 对齐关系查询索引
        tx.run("CREATE INDEX net_by_module_name IF NOT EXISTS FOR (n:Net) ON (n.module, n.name)")
      }
    }
  }

  
  def importNetlist(netlist: YosysRoot): NetlistImportStats = {
    logger.info("Starting import...")

    logger.info(s"Found ${netlist.modules.size} modules in the netlist.")
    netlist.modules.foreach { case (moduleName, module) =>
      logger.info(
        s"Module '$moduleName' has ${module.cells.size} cells, ${module.netnames.size} nets, and ${module.ports.size} ports."
      )
    }

    case class ModuleImportResult(moduleName: String, cells: Int, nets: Int, signals: Int, ports: Int)

    def importOne(moduleName: String, module: YosysModule): ModuleImportResult = {
      Using(driver.session()) { session =>
        session.executeWrite { tx =>
          importModuleInTransaction(tx, moduleName, module)
          val (bitToNetMap, netCount, signalCount) = importNetsAndSignalsInTransaction(tx, moduleName, module)
          val (cellCount, portCount) = importCellsAndPortsInTransaction(tx, moduleName, module, bitToNetMap)
          ModuleImportResult(moduleName, cellCount, netCount, signalCount, portCount)
        }
      } match {
        case Success(res) =>
          logger.info(s"Imported module: ${res.moduleName} (nets=${res.nets}, signals=${res.signals}, cells=${res.cells}, ports=${res.ports})")
          res
        case Failure(ex) =>
          logger.error(s"Netlist import failed for module $moduleName: ${ex.getMessage}", ex)
          throw ex
      }
    }

    val moduleEntries = netlist.modules.toList
    val totalModules = moduleEntries.size
    val progress = new AtomicInteger(0)

    val moduleResults: Seq[ModuleImportResult] =
      if (parallelModules && moduleEntries.size > 1) {
        import scala.concurrent.{Await, Future}
        import scala.concurrent.duration.*
        import scala.concurrent.ExecutionContext
        import java.util.concurrent.Executors

        val poolSize = math.max(2, math.min(4, Runtime.getRuntime.availableProcessors()))
        val executor = Executors.newFixedThreadPool(poolSize)
        given ExecutionContext = ExecutionContext.fromExecutor(executor)

        try {
          val futures = moduleEntries.map { case (name, mod) =>
            Future {
              val res = importOne(name, mod)
              val done = progress.incrementAndGet()
              val pct = (done * 100) / totalModules
              logger.info(s"Netlist import progress: $done/$totalModules (${pct}%) last=${res.moduleName}")
              res
            }
          }
          Await.result(Future.sequence(futures), Duration.Inf)
        } finally {
          executor.shutdown()
        }
      } else {
        moduleEntries.map { case (name, mod) =>
          val res = importOne(name, mod)
          val done = progress.incrementAndGet()
          val pct = (done * 100) / totalModules
          logger.info(s"Netlist import progress: $done/$totalModules (${pct}%) last=${res.moduleName}")
          res
        }
      }

    val totalModuleCount = moduleResults.length
    val totalCellCount = moduleResults.map(_.cells).sum
    val totalNetCount = moduleResults.map(_.nets).sum
    val totalSignalCount = moduleResults.map(_.signals).sum
    val totalPortCount = moduleResults.map(_.ports).sum

    // Link parameterized modules to their base modules
    linkParameterizedModules(netlist)

    logger.info("Import completed.")
    logger.info("Import statistics:")
    logger.info(s"  Modules: $totalModuleCount")
    logger.info(s"  Cells: $totalCellCount")
    logger.info(s"  Nets: $totalNetCount")
    logger.info(s"  Signals: $totalSignalCount")
    logger.info(s"  Ports: $totalPortCount")

    NetlistImportStats(
      modules = totalModuleCount,
      cells = totalCellCount,
      nets = totalNetCount,
      signals = totalSignalCount,
      ports = totalPortCount
    )
  }

  /**
   * 链接参数化模块到基础模块
   */
  private def linkParameterizedModules(netlist: YosysRoot): Unit = {
    Using(driver.session()) { session =>
      session.executeWrite { tx =>
        netlist.modules.keys.foreach { netlistModuleName =>
          if (netlistModuleName.startsWith("$paramod$")) {
            val baseModuleName = extractBaseModuleName(netlistModuleName)

            if (netlist.modules.contains(baseModuleName)) {
              logger.info(s"Linking parameterized module $netlistModuleName to base module $baseModuleName")

              tx.run(
                """MATCH (paramod:Module {name: $paramodName}), (base:Module {name: $baseName})
                  |MERGE (paramod)-[:PARAMETERIZED_INSTANCE_OF]->(base)
                  |SET paramod.isParameterized = true,
                  |    paramod.baseModule = $baseName,
                  |    paramod.moduleType = 'parameterized'""".stripMargin,
                Map(
                  "paramodName" -> netlistModuleName,
                  "baseName" -> baseModuleName
                ).asJava
              )
            }
          }
        }
      }
    }
  }

  /**
   * 从参数化模块名中提取基础模块名
   */
  private def extractBaseModuleName(paramodName: String): String = {
    // 格式: $paramod$<hash>\<base_module_name>
    val pattern = """.*\\(.+)""".r
    paramodName match {
      case pattern(baseName) => baseName
      case _ => ""
    }
  }

  def importAST(astRoot: ASTRoot): ASTImportStats = {
    logger.info("Starting AST import...")
    val expectedModuleCount = astRoot.modules.length
    val expectedBlockCount = astRoot.modules.map(_.blocks.length).sum
    val expectedSignalCount = astRoot.modules.map(m => m.ports.length + m.internalSignals.length).sum
    val expectedContain = astRoot.graph.edges.collect { case _: ASTContainEdge => 1 }.sum
    val expectedInstantiate = astRoot.graph.edges.collect { case _: ASTInstantiateEdge => 1 }.sum
    val expectedEdgeCount = expectedContain + expectedInstantiate

    logger.info(s"AST root has $expectedModuleCount modules and $expectedEdgeCount edges (contain=$expectedContain, instantiate=$expectedInstantiate, blocks: $expectedBlockCount, signals: $expectedSignalCount)")

    var totalModuleCount = 0
    var totalBlockCount = 0
    var totalSignalCount = 0
    var totalContain = 0
    var totalInstantiate = 0

    val result = Using(driver.session()) { session =>
      // 1) Import module nodes (no edges/contents yet)
      astRoot.modules.foreach { module =>
        logger.info(s"Importing AST module: ${module.name}")
        logger.debug(s"Module ${module.name} has ${module.ports.length} ports, ${module.internalSignals.length} signals, ${module.blocks.length} blocks")
        session.executeWrite { tx =>
          importASTModuleInTransaction(tx, module)
        }
        totalModuleCount += 1
      }

      // 2) Import module contents (optionally in parallel, each with its own session)
      import scala.concurrent.{Await, Future}
      import scala.concurrent.ExecutionContext.Implicits.global
      import scala.concurrent.duration.*

      val tasks = astRoot.modules.map { module =>
        Future {
          Using(driver.session()) { s =>
            s.executeWrite { tx =>
              importModuleContentsInTransaction(tx, module)
            }
          }.get
        }.map { case (blockCount, signalCount) => (module.name, blockCount, signalCount) }
      }

      val results =
        if (parallelModules) Await.result(Future.sequence(tasks), Duration.Inf)
        else tasks.map(Await.result(_, Duration.Inf)) // effectively serial

      results.foreach { case (moduleName, blockCount, signalCount) =>
        totalBlockCount += blockCount
        totalSignalCount += signalCount
        logger.debug(s"Imported module $moduleName: $blockCount blocks, $signalCount signals")
      }

      // 3) Import edges
      logger.info(s"Importing ${astRoot.graph.edges.length} AST edges...")
      session.executeWrite { tx =>
        val (containCount, instantiateCount) = importASTEdgesInTransaction(tx, astRoot.graph.edges)
        totalContain = containCount
        totalInstantiate = instantiateCount
      }
    }
    result.recover { case ex =>
      logger.error(s"AST import failed: ${ex.getMessage}", ex)
      throw ex
    }

    logger.info("AST import completed.")
    logger.info("AST import statistics:")
    logger.info(s"  AST Modules: $totalModuleCount")
    logger.info(s"  Blocks: $totalBlockCount")
    logger.info(s"  Signals: $totalSignalCount")
    logger.info(s"  Edges: ${totalContain + totalInstantiate} (contain=$totalContain, instantiate=$totalInstantiate)")

    if (totalModuleCount != expectedModuleCount || totalBlockCount != expectedBlockCount || totalSignalCount != expectedSignalCount || (totalContain + totalInstantiate) != expectedEdgeCount) {
      logger.warn(s"AST import counts differ from parsed AST (expected modules=$expectedModuleCount, blocks=$expectedBlockCount, signals=$expectedSignalCount, edges=$expectedEdgeCount)")
    }

    ASTImportStats(
      modules = totalModuleCount,
      blocks = totalBlockCount,
      signals = totalSignalCount,
      edges = totalContain + totalInstantiate
    )
  }

  private def normName(name: String): String = name.stripPrefix("\\")

  private def signalAliases(name: String): Set[String] = {
    val base = normName(name)
    val short = base.split('.').lastOption.getOrElse(base)
    Set(name, base, short).filter(_.nonEmpty)
  }

  def importDFG(
    dfgGraph: DFGGraph,
    astRoot: ASTRoot,
    alignToAst: Boolean = true
  ): DFGImportStats = {
    if (dfgGraph.nodes.isEmpty) {
      logger.info("Skip DFG import: no nodes")
      return DFGImportStats(0, 0, 0, 0)
    }

    val astSignalByModuleName = scala.collection.mutable.Map[(String, String), List[String]]()
    val astSignalByName = scala.collection.mutable.Map[String, List[String]]()
    val astBlockRanges = scala.collection.mutable.Map[String, List[(Int, Int, String)]]()

    astRoot.modules.foreach { module =>
      val moduleId = ASTId.moduleId(module)
      val moduleName = normName(module.name)

      val allSignals = module.ports ++ module.internalSignals
      allSignals.foreach { signal =>
        val signalId =
          if (Set("port", "input", "output", "inout").contains(signal.signalType))
            ASTId.portId(moduleId, signal.name)
          else
            ASTId.signalId(moduleId, signal.name)

        signalAliases(signal.name).foreach { alias =>
          val key = (moduleName, alias)
          val updated = signalId :: astSignalByModuleName.getOrElse(key, Nil)
          astSignalByModuleName.update(key, updated.distinct)

          val globalUpdated = signalId :: astSignalByName.getOrElse(alias, Nil)
          astSignalByName.update(alias, globalUpdated.distinct)
        }
      }

      module.blocks.foreach { block =>
        block.sourceRange.foreach { range =>
          val displayName = block.name.getOrElse(s"${block.blockType}_${range.startLine}")
          val blockId = ASTId.blockId(moduleId, displayName)
          val existing = astBlockRanges.getOrElse(moduleName, Nil)
          astBlockRanges.update(moduleName, (range.startLine, range.endLine, blockId) :: existing)
        }
      }
    }

    var createdNodes = 0
    var createdEdges = 0
    var createdRefs = 0
    var createdAnchors = 0

    val nodePayload = dfgGraph.nodes.map { node =>
      Map(
        "id" -> node.id,
        "name" -> node.name,
        "nodeKind" -> node.nodeKind,
        "module" -> node.module.getOrElse(""),
        "line" -> node.line.getOrElse(0),
        "operator" -> node.operator.getOrElse("")
      ).asJava
    }

    val nodeIdSet = dfgGraph.nodes.map(_.id).toSet

    val edgePayload = dfgGraph.edges
      .filter(e => nodeIdSet.contains(e.src) && nodeIdSet.contains(e.dst))
      .map { edge =>
        Map(
          "src" -> edge.src,
          "dst" -> edge.dst,
          "edgeType" -> edge.edgeType,
          "line" -> edge.line.getOrElse(0)
        ).asJava
      }

    val refPayload =
      if (!alignToAst) Nil
      else {
        dfgGraph.nodes.flatMap { node =>
          val module = normName(node.module.getOrElse(""))
          val aliases = signalAliases(node.name)
          val moduleMatches =
            if (module.nonEmpty)
              aliases.toList.flatMap(alias => astSignalByModuleName.getOrElse((module, alias), Nil))
            else Nil
          val matches =
            if (moduleMatches.nonEmpty) moduleMatches
            else aliases.toList.flatMap(alias => astSignalByName.getOrElse(alias, Nil))

          matches.distinct.take(3).map { astSignalId =>
            Map("dfgId" -> node.id, "astSignalId" -> astSignalId).asJava
          }
        }
      }

    val anchorPayload =
      if (!alignToAst) Nil
      else {
        dfgGraph.nodes.flatMap { node =>
          val module = normName(node.module.getOrElse(""))
          val lineOpt = node.line
          if (module.isEmpty || lineOpt.isEmpty) Nil
          else {
            val line = lineOpt.get
            val candidates = astBlockRanges.getOrElse(module, Nil).filter { case (start, end, _) =>
              line >= start && line <= end
            }
            if (candidates.isEmpty) Nil
            else {
              val best = candidates.minBy { case (start, end, _) => end - start }
              List(Map("dfgId" -> node.id, "astBlockId" -> best._3).asJava)
            }
          }
        }
      }

    Using(driver.session()) { session =>
      session.executeWrite { tx =>
        nodePayload.grouped(safeBatchSize).foreach { batch =>
          val summary = tx.run(
            """UNWIND $rows AS row
              |MERGE (d:DFGNode {id: row.id})
              |SET d.name = row.name,
              |    d.nodeKind = row.nodeKind,
              |    d.module = row.module,
              |    d.line = row.line,
              |    d.operator = row.operator""".stripMargin,
            Map("rows" -> batch.asJava).asJava
          ).consume()
          createdNodes += summary.counters().nodesCreated()
        }

        edgePayload.grouped(safeBatchSize).foreach { batch =>
          val summary = tx.run(
            """UNWIND $rows AS row
              |MATCH (src:DFGNode {id: row.src}), (dst:DFGNode {id: row.dst})
              |CREATE (src)-[:DFG_FLOW {type: row.edgeType, line: row.line}]->(dst)""".stripMargin,
            Map("rows" -> batch.asJava).asJava
          ).consume()
          createdEdges += summary.counters().relationshipsCreated()
        }

        refPayload.grouped(safeBatchSize).foreach { batch =>
          val summary = tx.run(
            """UNWIND $rows AS row
              |MATCH (d:DFGNode {id: row.dfgId}), (s:ASTSignal {id: row.astSignalId})
              |MERGE (d)-[:REFERS_AST_SIGNAL]->(s)""".stripMargin,
            Map("rows" -> batch.asJava).asJava
          ).consume()
          createdRefs += summary.counters().relationshipsCreated()
        }

        anchorPayload.grouped(safeBatchSize).foreach { batch =>
          val summary = tx.run(
            """UNWIND $rows AS row
              |MATCH (d:DFGNode {id: row.dfgId}), (b:ASTBlock {id: row.astBlockId})
              |MERGE (d)-[:ANCHOR_BLOCK]->(b)""".stripMargin,
            Map("rows" -> batch.asJava).asJava
          ).consume()
          createdAnchors += summary.counters().relationshipsCreated()
        }
      }
    }

    logger.info(
      s"DFG import completed: nodes=${dfgGraph.nodes.size}, edges=${edgePayload.size}, " +
        s"refs=$createdRefs, anchors=$createdAnchors"
    )

    DFGImportStats(
      nodes = dfgGraph.nodes.size,
      edges = edgePayload.size,
      signalRefs = createdRefs,
      blockAnchors = createdAnchors
    )
  }

  private def importASTModuleInTransaction(tx: TransactionContext, module: ASTModuleNode): Unit = {
    val moduleId = ASTId.moduleId(module)

    // 存储SourceRange信息
    val sourceRangeMap = module.sourceRange match {
      case Some(range) => Map(
        "source_file" -> range.file,
        "start_line" -> range.startLine,
        "end_line" -> range.endLine
      )
      case None => Map.empty[String, Any]
    }

    tx.run(
      """CREATE (m:ASTModule {id: $id})
        |SET m.name = $name,
        |    m.type = 'ASTModule',
        |    m.file = $file,
        |    m.line = $line,
        |    m.memoryAddress = $memoryAddress,
        |    m.flags = $flags,
        |    m += $sourceRange""".stripMargin,
      Map(
        "id" -> moduleId,
        "name" -> module.name,
        "file" -> module.attributes.sourcePosition.file.getOrElse(""),
        "line" -> module.attributes.sourcePosition.line.getOrElse(0),
        "memoryAddress" -> module.attributes.memoryAddress.getOrElse(""),
        "flags" -> module.attributes.flags.asJava,
        "sourceRange" -> sourceRangeMap.asJava
      ).asJava
    )
  }

  private def importModuleContentsInTransaction(tx: TransactionContext, module: ASTModuleNode): (Int, Int) = {
    val moduleId = ASTId.moduleId(module)
    var blockCount = 0
    var signalCount = 0
    // 建立信号名到ID的映射，便于 Block->Signal 连接
    val signalIdMap: Map[String, String] =
      module.ports.map(p => p.name -> ASTId.portId(moduleId, p.name)).toMap ++
        module.internalSignals.map(s => s.name -> ASTId.signalId(moduleId, s.name)).toMap

    val signalPayload = (module.ports ++ module.internalSignals).map { signal =>
      val isPort = module.ports.exists(_.name == signal.name) &&
        Set("port", "input", "output", "inout").contains(signal.signalType)
      val signalId =
        if (isPort || Set("port", "input", "output", "inout").contains(signal.signalType))
          ASTId.portId(moduleId, signal.name)
        else
          ASTId.signalId(moduleId, signal.name)
      Map(
        "id" -> signalId,
        "name" -> signal.name,
        "signalType" -> signal.signalType,
        "width" -> signal.width.getOrElse(0),
        "signed" -> signal.signed,
        "file" -> signal.attributes.sourcePosition.file.getOrElse(""),
        "line" -> signal.attributes.sourcePosition.line.getOrElse(0),
        "memoryAddress" -> signal.attributes.memoryAddress.getOrElse(""),
        "flags" -> signal.attributes.flags.asJava,
        "module" -> module.name
      ).asJava
    }

    signalPayload.grouped(safeBatchSize).foreach { batch =>
      val summary = tx.run(
        """UNWIND $signals AS sig
          |CREATE (s:ASTSignal {id: sig.id})
          |SET s.name = sig.name,
          |    s.type = sig.signalType,
          |    s.width = sig.width,
          |    s.signed = sig.signed,
          |    s.file = sig.file,
          |    s.line = sig.line,
          |    s.memoryAddress = sig.memoryAddress,
          |    s.flags = sig.flags,
          |    s.module = sig.module""".stripMargin,
        Map("signals" -> batch.asJava).asJava
      ).consume()
      signalCount += summary.counters().nodesCreated()
    }

    // Import blocks
    val blockPayload = module.blocks.map { block =>
      val blockDisplayName = block.name.getOrElse(s"${block.blockType}_${block.sourceRange.map(_.startLine).getOrElse(0)}")
      val blockId = ASTId.blockId(moduleId, blockDisplayName)

      val blockSourceRangeMap = block.sourceRange match {
        case Some(range) => Map(
          "source_file" -> range.file,
          "start_line" -> range.startLine,
          "end_line" -> range.endLine
        ).asJava
        case None => Map.empty[String, Any].asJava
      }

      val uses = block.referencedSignals.flatMap(sigName => signalIdMap.get(sigName).map(sid => Map("blockId" -> blockId, "signalId" -> sid).asJava))

      (Map(
        "id" -> blockId,
        "name" -> block.name.getOrElse(""),
        "blockType" -> block.blockType,
        "file" -> block.attributes.sourcePosition.file.getOrElse(""),
        "line" -> block.attributes.sourcePosition.line.getOrElse(0),
        "instanceName" -> block.instanceName.getOrElse(""),
        "memoryAddress" -> block.attributes.memoryAddress.getOrElse(""),
        "flags" -> block.attributes.flags.asJava,
        "module" -> module.name,
        "sourceRange" -> blockSourceRangeMap
      ).asJava, uses, block, blockId)
    }

    blockPayload.map(_._1).grouped(safeBatchSize).foreach { batch =>
      val summary = tx.run(
        """UNWIND $blocks AS block
          |CREATE (b:ASTBlock {id: block.id})
          |SET b.name = block.name,
          |    b.blockType = block.blockType,
          |    b.file = block.file,
          |    b.line = block.line,
          |    b.instanceName = block.instanceName,
          |    b.memoryAddress = block.memoryAddress,
          |    b.flags = block.flags,
          |    b.module = block.module,
          |    b += block.sourceRange""".stripMargin,
        Map("blocks" -> batch.asJava).asJava
      ).consume()
      blockCount += summary.counters().nodesCreated()
    }

    // Import block ports if function/task (counts small, keep simple)
    blockPayload.foreach { case (_, _, block, blockId) =>
      block.ports.foreach { port =>
        val blockPortId = ASTId.portId(blockId, port.name)
        tx.run(
          """CREATE (bp:ASTSignal {id: $id})
            |SET bp.name = $name,
            |    bp.type = $signalType,
            |    bp.width = $width,
            |    bp.signed = $signed,
            |    bp.file = $file,
            |    bp.line = $line,
            |    bp.module = $module""".stripMargin,
          Map(
            "id" -> blockPortId,
            "name" -> port.name,
            "signalType" -> port.signalType,
            "width" -> port.width.getOrElse(0),
            "signed" -> port.signed,
            "file" -> port.attributes.sourcePosition.file.getOrElse(""),
            "line" -> port.attributes.sourcePosition.line.getOrElse(0),
            "module" -> module.name
          ).asJava
        )
        signalCount += 1
      }
    }

    // Embedding: Module + Blocks (optional)
    if (generateEmbeddings && embeddingConfig.nonEmpty) {
      import scala.concurrent.ExecutionContext.Implicits.global
      val texts = scala.collection.mutable.ArrayBuffer[(String, String)]()

      module.sourceRange.flatMap(loadCode).foreach { code =>
        texts += ((moduleId, code))
      }
      blockPayload.foreach { case (_, _, block, blockId) =>
        block.sourceRange.flatMap(loadCode).foreach { code =>
          texts += ((blockId, code))
        }
      }

      if (texts.nonEmpty) {
        val cfg = embeddingConfig.get
        val inputSeq = texts.toSeq
        val embBatch = math.max(1, cfg.batchSize)
        inputSeq.grouped(embBatch).foreach { chunk =>
          val vectors = Await.result(EmbeddingService.embed(chunk.map(_._2), cfg), Duration.Inf)
          val payload = chunk.zip(vectors).map { case ((id, _), vec) =>
            Map(
              "id" -> id,
              "embedding" -> vec.map(_.toDouble).toList.asJava
            ).asJava
          }.asJava

          tx.run(
            """UNWIND $rows AS row
              |MATCH (n {id: row.id})
              |SET n.embedding = row.embedding""".stripMargin,
            Map("rows" -> payload).asJava
          )
        }
      }
    }

    (blockCount, signalCount)
  }

  private def importASTEdgesInTransaction(tx: TransactionContext, edges: List[ASTEdge]): (Int, Int) = {
    val containEdges = edges.collect { case e: ASTContainEdge => e }
    val instantiateEdges = edges.collect { case e: ASTInstantiateEdge => e }
    var containCreated = 0
    var instantiateCreated = 0

    containEdges.grouped(safeBatchSize).foreach { batch =>
      val payload = batch.map { edge =>
        Map(
          "fromId" -> edge.fromNodeId,
          "toId" -> edge.toNodeId,
          "containType" -> edge.containType,
          "file" -> edge.attributes.sourcePosition.file.getOrElse(""),
          "line" -> edge.attributes.sourcePosition.line.getOrElse(0),
          "memoryAddress" -> edge.attributes.memoryAddress.getOrElse(""),
          "flags" -> edge.attributes.flags.toList.asJava
        ).asJava
      }.asJava

      val summary = tx.run(
        """UNWIND $edges AS edge
          |MATCH (from:ASTModule|ASTBlock|ASTSignal {id: edge.fromId}),
          |      (to:ASTModule|ASTBlock|ASTSignal {id: edge.toId})
          |CREATE (from)-[r:CONTAIN]->(to)
          |SET r.type = edge.containType,
          |    r.file = edge.file,
          |    r.line = edge.line,
          |    r.memoryAddress = edge.memoryAddress,
          |    r.flags = edge.flags""".stripMargin,
        Map("edges" -> payload).asJava
      ).consume()
      containCreated += summary.counters().relationshipsCreated()
    }

    instantiateEdges.grouped(safeBatchSize).foreach { batch =>
      val payload = batch.map { edge =>
        Map(
          "fromId" -> edge.fromNodeId,
          "toId" -> edge.toNodeId,
          "instanceName" -> edge.instanceName,
          "connections" -> edge.connections.map { c =>
            Map("portName" -> c.portName, "signalName" -> c.signalName).asJava
          }.asJava,
          "file" -> edge.attributes.sourcePosition.file.getOrElse(""),
          "line" -> edge.attributes.sourcePosition.line.getOrElse(0),
          "memoryAddress" -> edge.attributes.memoryAddress.getOrElse(""),
          "flags" -> edge.attributes.flags.toList.asJava
        ).asJava
      }.asJava

      val summary = tx.run(
        """UNWIND $edges AS edge
          |MATCH (from:ASTModule|ASTBlock {id: edge.fromId}),
          |      (to:ASTModule {id: edge.toId})
          |CREATE (from)-[r:INSTANTIATE]->(to)
          |SET r.instanceName = edge.instanceName,
          |    r.connections = edge.connections,
          |    r.file = edge.file,
          |    r.line = edge.line,
          |    r.memoryAddress = edge.memoryAddress,
          |    r.flags = edge.flags""".stripMargin,
        Map("edges" -> payload).asJava
      ).consume()
      instantiateCreated += summary.counters().relationshipsCreated()
    }

    if (containCreated == 0 && instantiateCreated == 0) {
      logger.debug("AST edge import created no relationships in this pass.")
    }

    (containCreated, instantiateCreated)
  }

  def importAlignment(alignment: AlignmentData): Unit = {
    Using(driver.session()) { session =>
      session.executeWrite { tx =>
        val modulesPayload = alignment.moduleLinks.map { link =>
          Map("astId" -> link.astModuleId, "moduleName" -> link.moduleName).asJava
        }.asJava
        if (!modulesPayload.isEmpty) {
          tx.run(
            """UNWIND $rows AS row
              |MATCH (am:ASTModule {id: row.astId}), (m:Module {name: row.moduleName})
              |MERGE (am)-[:ALIGN_MODULE]->(m)""".stripMargin,
            Map("rows" -> modulesPayload).asJava
          )
        }

        val sigPayload = alignment.signalLinks.map { link =>
          Map(
            "astSignalId" -> link.astSignalId,
            "moduleName" -> link.moduleName,
            "netName" -> link.netName
          ).asJava
        }.asJava
        if (!sigPayload.isEmpty) {
          tx.run(
            """UNWIND $rows AS row
              |MATCH (asig:ASTSignal {id: row.astSignalId}), (n:Net {name: row.netName, module: row.moduleName})
              |MERGE (asig)-[:ALIGN_SIGNAL]->(n)""".stripMargin,
            Map("rows" -> sigPayload).asJava
          )
        }

        val netBlockPayload = alignment.netBlockLinks.map { link =>
          Map(
            "moduleName" -> link.moduleName,
            "netName" -> link.netName,
            "astBlockId" -> link.astBlockId
          ).asJava
        }.asJava
        if (!netBlockPayload.isEmpty) {
          tx.run(
            """UNWIND $rows AS row
              |MATCH (n:Net {name: row.netName, module: row.moduleName}), (b:ASTBlock {id: row.astBlockId})
              |MERGE (n)-[:ALIGN_BLOCK]->(b)""".stripMargin,
            Map("rows" -> netBlockPayload).asJava
          )
        }

        val cellBlockPayload = alignment.cellBlockLinks.map { link =>
          Map(
            "cellId" -> link.cellId,
            "astBlockId" -> link.astBlockId
          ).asJava
        }.asJava
        if (!cellBlockPayload.isEmpty) {
          tx.run(
            """UNWIND $rows AS row
              |MATCH (c:Cell {id: row.cellId}), (b:ASTBlock {id: row.astBlockId})
              |MERGE (c)-[:ALIGN_BLOCK]->(b)""".stripMargin,
            Map("rows" -> cellBlockPayload).asJava
          )
        }
      }
    }
  }

  private def importModuleInTransaction(
      tx: TransactionContext,
      moduleName: String,
      module: YosysModule
  ): Unit = {
    tx.run(
      "CREATE (m:Module {name: $name}) SET m += $attributes",
      Map(
        "name" -> moduleName,
        "attributes" -> module.attributes.asJava
      ).asJava
    )
  }

  private def importNetsAndSignalsInTransaction(
      tx: TransactionContext,
      moduleName: String,
      module: YosysModule
  ): (Map[Int, String], Int, Int) = {
    logger.info("Inside importNetsAndSignals")
    val bitToNetMap = scala.collection.mutable.Map[Int, String]()

    // Nets payload
    val netsPayload = module.netnames.map { case (netName, net) =>
      Map(
        "name" -> netName,
        "moduleName" -> moduleName,
        "hideName" -> net.hide_name,
        "attributes" -> net.attributes.asJava
      ).asJava
    }.toList

    netsPayload.grouped(safeBatchSize).foreach { batch =>
      val groups = batch.grouped(netBatchSize).toList
      groups.foreach { g =>
      tx.run(
        """UNWIND $nets AS net
          |CREATE (n:Net {name: net.name, module: net.moduleName})
          |SET n.hide_name = net.hideName,
          |    n += net.attributes""".stripMargin,
        Map("nets" -> g.asJava).asJava
      ).consume()
      }
    }
    val netCount = netsPayload.size

    // Signals payload (per bit) and HAS_SIGNAL rels
    val signalPayload = module.netnames.toList.flatMap { case (netName, net) =>
      net.bits.collect {
        case Left(bit) if bit >= 0 =>
          bitToNetMap(bit) = netName
          Map(
            "bit" -> bit,
            "moduleName" -> moduleName,
            "netName" -> netName
          ).asJava
      }
    }

    var signalCount = 0
    signalPayload.grouped(netBatchSize).foreach { batch =>
      val summary = tx.run(
        """UNWIND $signals AS sig
          |MATCH (n:Net {name: sig.netName, module: sig.moduleName})
          |CREATE (s:Signal {id: sig.bit, module: sig.moduleName})
          |CREATE (n)-[:HAS_SIGNAL]->(s)""".stripMargin,
        Map("signals" -> batch.asJava).asJava
      ).consume()
      signalCount += summary.counters().nodesCreated()
    }

    (bitToNetMap.toMap, netCount, signalCount)
  }

  private def importCellsAndPortsInTransaction(
      tx: TransactionContext,
      moduleName: String,
      module: YosysModule,
      bitToNetMap: Map[Int, String]
  ): (Int, Int) = {
    logger.info("Inside importCellsAndPorts")

    val cellsPayload = module.cells.map { case (cellName, cell) =>
      val cellId = s"$moduleName.$cellName"
      Map(
        "id" -> cellId,
        "moduleName" -> moduleName,
        "cellType" -> cell.cell_type,
        "hideName" -> cell.hide_name,
      "parameters" -> cell.parameters.asJava,
      "attributes" -> cell.attributes.asJava
    ).asJava
    }.toList

    cellsPayload.grouped(safeBatchSize).foreach { batch =>
      val groups = batch.grouped(netBatchSize).toList
      groups.foreach { g =>
      tx.run(
        """UNWIND $cells AS cell
          |MATCH (m:Module {name: cell.moduleName})
          |CREATE (c:Cell {id: cell.id})
          |SET c.type = cell.cellType,
          |    c.hide_name = cell.hideName,
          |    c += cell.parameters,
          |    c += cell.attributes
          |CREATE (m)-[:CONTAINS]->(c)""".stripMargin,
        Map("cells" -> g.asJava).asJava
      ).consume()
      }
    }
    val cellCount = cellsPayload.size

    // Build ports payload and connection payload
    val portPayload = scala.collection.mutable.ListBuffer[java.util.Map[String, Any]]()
    val connPayload = scala.collection.mutable.ListBuffer[java.util.Map[String, Any]]()

    module.cells.foreach { case (cellName, cell) =>
      val cellId = s"$moduleName.$cellName"
      cell.connections.foreach { case (portName, bits) =>
        val portId = s"$cellId.$portName"
        val direction = cell.port_directions.getOrElse(portName, "unknown")
        portPayload += Map(
          "cellId" -> cellId,
          "portId" -> portId,
          "portName" -> portName,
          "direction" -> direction
        ).asJava

        bits.foreach {
          case json if json.isNumber =>
            json.asNumber.flatMap(_.toInt).foreach { bit =>
              if (bit >= 0) {
                bitToNetMap.get(bit).foreach { netName =>
                  val rel = if (direction == "output") "DRIVES" else "RIDES"
                  connPayload += Map(
                    "portId" -> portId,
                    "netName" -> netName,
                    "moduleName" -> moduleName,
                    "rel" -> rel
                  ).asJava
                }
              }
            }
          case _ => // ignore
        }
      }
    }

    portPayload.grouped(netBatchSize).foreach { batch =>
      tx.run(
        """UNWIND $ports AS port
          |MATCH (c:Cell {id: port.cellId})
          |CREATE (p:Port {id: port.portId})
          |SET p.name = port.portName,
          |    p.direction = port.direction
          |CREATE (c)-[:HAS_PORT]->(p)""".stripMargin,
        Map("ports" -> batch.asJava).asJava
      ).consume()
    }
    val portCount = portPayload.size

    // Split connections by rel type to avoid dynamic relationship type
    val drivesPayload = connPayload.filter(_.get("rel") == "DRIVES")
    val ridesPayload = connPayload.filter(_.get("rel") == "RIDES")

    drivesPayload.grouped(netBatchSize).foreach { batch =>
      tx.run(
        """UNWIND $conns AS c
          |MATCH (p:Port {id: c.portId}), (n:Net {name: c.netName, module: c.moduleName})
          |CREATE (p)-[:DRIVES]->(n)""".stripMargin,
        Map("conns" -> batch.asJava).asJava
      )
    }

    ridesPayload.grouped(netBatchSize).foreach { batch =>
      tx.run(
        """UNWIND $conns AS c
          |MATCH (p:Port {id: c.portId}), (n:Net {name: c.netName, module: c.moduleName})
          |CREATE (p)-[:RIDES]->(n)""".stripMargin,
        Map("conns" -> batch.asJava).asJava
      )
    }

    (cellCount, portCount)
  }

  /**
   * 生成和存储embedding到现有节点（只对Module和Block）
   */
  private def loadCode(range: SourceRange): Option[String] = {
    val file = new File(range.file)
    if (!file.exists()) {
      return None
    }
    Using(Source.fromFile(file)) { src =>
      src.getLines().slice(math.max(0, range.startLine - 1), range.endLine).mkString("\n")
    }.toOption
  }
}

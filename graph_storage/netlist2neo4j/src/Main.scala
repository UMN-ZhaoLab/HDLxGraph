package netlist2neo4j

import io.circe.parser.*
import org.neo4j.driver.*
import com.typesafe.scalalogging.Logger
import scala.io.Source
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.{Try, Using, Success, Failure}
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Main {
  val logger = Logger("netlist2neo4j")

  private def renderProgress(label: String, current: Int, total: Int, width: Int = 30): String = {
    val safeTotal = math.max(total, 1)
    val ratio = current.toDouble / safeTotal
    val filled = math.round(ratio * width).toInt
    val bar = "=" * filled + "-" * (width - filled)
    val prefix = if (label.nonEmpty) s"$label " else ""
    f"$prefix[$bar] $current/$total (${ratio * 100}%.1f%%)"
  }

  def printProgress(label: String, current: Int, total: Int): Unit = {
    val message = renderProgress(label, current, total)
    val lineEnd = if (current >= total) "\n" else ""
    System.out.print("\r" + message + lineEnd)
    System.out.flush()
  }

  def printProgress(current: Int, total: Int): Unit = {
    printProgress("", current, total)
  }

  case class CliConfig(
    configFile: Option[String] = None,
    inputPath: Option[String] = None
  )

  
  def parseArgs(args: List[String]): CliConfig =
    def recurse(remaining: List[String], config: CliConfig): CliConfig =
      remaining match
        case Nil => config
        case ("--help" | "-h") :: tail =>
          Main.printUsage()
          sys.exit(0)
        case "--config" :: configFile :: tail =>
          recurse(tail, config.copy(configFile = Some(configFile)))
        case "--generate-config" :: Nil =>
          // 生成示例配置文件并退出
          println("Sample configuration file:")
          println(ConfigManager.generateSampleConfig())
          sys.exit(0)
        case arg :: tail if !arg.startsWith("--") =>
          // 第一个非选项参数作为输入路径（保持最小CLI），其余忽略
          config.inputPath match
            case None => recurse(tail, config.copy(inputPath = Some(arg)))
            case Some(_) =>
              logger.warn(s"Ignoring extra positional argument: $arg")
              recurse(tail, config)
        case unknown :: tail =>
          logger.warn(s"Ignoring unknown option: $unknown")
          recurse(tail, config)

    recurse(args, CliConfig())

  def readJsonFile(filePath: String): Try[String] = {
    Try(scala.io.Source.fromFile(filePath).mkString)
  }

  def parseJsonContent(jsonContent: String): Try[YosysRoot] = {
    decode[YosysRoot](jsonContent) match {
      case Right(netlist) => Success(netlist)
      case Left(error) => scala.util.Failure(new Exception(s"Error parsing JSON: $error"))
    }
  }

  /**
   * 读取包含Verilog文件列表的文件
   */
  def readBatchList(listFilePath: String): Try[List[String]] = {
    Try {
      val listFile = new File(listFilePath)
      val baseDir = Option(listFile.getParentFile).getOrElse(new File(".")).getCanonicalFile
      val source = Source.fromFile(listFile)
      val lines = source.getLines().toList
      source.close()

      // 过滤空行和注释行（以#开头）
      val fileList = lines
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .map { line =>
          val f = new File(line)
          if (f.isAbsolute) f.getAbsolutePath else new File(baseDir, line).getAbsolutePath
        }

      if (fileList.isEmpty) {
        throw new IllegalArgumentException(s"No valid Verilog files found in $listFilePath")
      }

      fileList
    }
  }

  /**
   * 批量处理多个Verilog文件
   */
  def processBatchFiles(
    verilogFiles: List[String],
    yosysConfig: YosysConfig,
    doNetlist: Boolean,
    parallel: Boolean
  ): Try[List[(String, Either[String, (YosysRoot, Option[ASTRoot])])]] = {
    val logger = Logger("netlist2neo4j")

    // Netlist：一次性读取全部文件，避免宏/包缺失（可按需跳过）
    val netlistResult: Try[YosysRoot] =
      if (doNetlist) {
        YosysIntegration.synthesizeVerilogToJSON(verilogFiles, yosysConfig).flatMap { synth =>
          val jsonPath = synth.jsonPath.getOrElse("")
          Main.readJsonFile(jsonPath).flatMap(Main.parseJsonContent)
        }
      } else Success(YosysRoot("skipped", Map.empty))
    val totalAstFiles = verilogFiles.length
    val yosysCounter = new AtomicInteger(0)
    val astCounter = new AtomicInteger(0)
    val progressLock = new Object

    def updateYosysProgress(): Unit = {
      val current = yosysCounter.incrementAndGet()
      progressLock.synchronized {
        Main.printProgress("Yosys", current, totalAstFiles)
      }
    }

    def withAstProgress(result: (String, Either[String, Option[ASTRoot]])): (String, Either[String, Option[ASTRoot]]) = {
      val current = astCounter.incrementAndGet()
      progressLock.synchronized {
        Main.printProgress("AST", current, totalAstFiles)
      }
      result
    }

    def runYosys(verilogPath: String): Either[String, String] =
      YosysIntegration.extractVerilogASTSimple(verilogPath, new java.io.File(yosysConfig.workDir), "yosys", yosysConfig) match {
        case Success(astPath) => Right(astPath)
        case Failure(err)     => Left(s"AST extraction failed: ${err.getMessage}")
      }

    def parseAst(verilogPath: String, astPath: String): (String, Either[String, Option[ASTRoot]]) =
      ASTParser.parseASTFile(astPath) match {
        case Success(astParseResult) =>
          (verilogPath, Right(Some(astParseResult.root)))
        case Failure(err) =>
          (verilogPath, Left(s"AST parsing failed: ${err.getMessage}"))
      }

    val astResults: List[(String, Either[String, Option[ASTRoot]])] =
      if (parallel && verilogFiles.size > 1) {
        val parallelism = math.max(1, Runtime.getRuntime.availableProcessors())
        val yosysExecutor = Executors.newFixedThreadPool(parallelism)
        val parserExecutor = Executors.newFixedThreadPool(parallelism)
        val yosysEc = ExecutionContext.fromExecutorService(yosysExecutor)
        val parserEc = ExecutionContext.fromExecutorService(parserExecutor)
        implicit val ec: ExecutionContext = yosysEc
        try {
          val futures = verilogFiles.map { path =>
            val yosysFuture = Future {
              val result = runYosys(path)
              updateYosysProgress()
              result
            }(yosysEc)

            yosysFuture.flatMap {
              case Right(astPath) =>
                Future(parseAst(path, astPath))(parserEc).map(withAstProgress)(parserEc)
              case Left(err) =>
                Future.successful(withAstProgress((path, Left(err))))
            }(yosysEc)
          }
          Await.result(Future.sequence(futures), Duration.Inf)
        } finally {
          yosysExecutor.shutdown()
          parserExecutor.shutdown()
        }
      } else {
        verilogFiles.map { path =>
          runYosys(path) match {
            case Right(astPath) =>
              updateYosysProgress()
              withAstProgress(parseAst(path, astPath))
            case Left(err) =>
              updateYosysProgress()
              withAstProgress((path, Left(err)))
          }
        }
      }

    netlistResult match {
      case Success(netParsed) =>
        Success(astResults.map {
          case (path, Right(astOpt)) => (path, Right((netParsed, astOpt)))
          case (path, Left(err))     => (path, Left(err))
        })
      case Failure(err) =>
        Success(verilogFiles.map(f => (f, Left(s"Netlist generation failed: ${err.getMessage}"))))
    }
  }

  def processVerilogFile(verilogPath: String, yosysConfig: YosysConfig, doNetlist: Boolean): Try[(YosysRoot, Option[ASTRoot])] = {
    val logger = Logger("netlist2neo4j")
    val workDirFile = new File(yosysConfig.workDir)

    // 生成AST
    val astResult = YosysIntegration.extractVerilogASTSimple(verilogPath, workDirFile, "yosys", yosysConfig) match {
      case Success(astPath) =>
        logger.info(s"AST generated: $astPath")
        // Parse AST file
        ASTParser.parseASTFile(astPath) match {
          case Success(astParseResult) =>
            logger.info(s"AST parsed successfully: ${astParseResult.nodeCount} nodes, ${astParseResult.edgeCount} edges")
            Success(Some(astParseResult.root))
          case Failure(error) =>
            logger.warn(s"AST parsing failed: ${error.getMessage}")
            Success(None)
        }
      case Failure(error) =>
        logger.warn(s"AST extraction failed: ${error.getMessage}")
        Success(None)
    }

    // 生成netlist
    val netlistResult =
      if (doNetlist) {
        YosysIntegration.synthesizeVerilogToJSON(List(verilogPath), yosysConfig).flatMap { result =>
          result.jsonPath match {
            case Some(jsonPath) =>
              val jsonContent = Main.readJsonFile(jsonPath)
              jsonContent match {
                case Success(content) =>
                  Main.parseJsonContent(content) match {
                    case Success(netlist) => Success(netlist)
                    case Failure(error) =>
                      Failure(new Exception(s"Error parsing generated JSON: ${error.getMessage}"))
                  }
                case Failure(error) =>
                  Failure(new Exception(s"Error reading generated JSON file: ${error.getMessage}"))
              }
            case None =>
              Failure(new Exception("No JSON file was generated"))
          }
        }
      } else Success(YosysRoot("skipped", Map.empty))

    // Combine results
    astResult.flatMap { astOpt =>
      netlistResult.map { netlist =>
        (netlist, astOpt)
      }
    }
  }

  /**
   * 加载配置文件
   */
  def loadConfig(cliConfig: CliConfig): Try[AppConfig] = {
    cliConfig.configFile match {
      case Some(configPath) =>
        ConfigManager.loadFromFile(configPath)
      case None =>
        logger.info("No configuration file specified, using default settings")
        Success(ConfigManager.createDefault())
    }
  }

  
  def printUsage(): Unit = {
    println("Usage:")
    println("  netlist2neo4j [--config <file>] <input_file>")
    println("")
    println("Options:")
    println("  --config <file>     Configuration file (JSON format)")
    println("  --generate-config   Generate sample configuration file and exit")
    println("  --help, -h          Show this help message")
    println("")
    println("Configuration File:")
    println("  Use --config <file> to specify a JSON configuration file.")
    println("  Generate a sample config with: netlist2neo4j --generate-config")
    println("")
    println("Examples:")
    println("  # Process JSON file with config")
    println("  netlist2neo4j --config config.json netlist.json")
    println("")
    println("  # Process Verilog file with config")
    println("  netlist2neo4j --config config.json design.v")
    println("")
    println("Configuration File Sections:")
    println("  - neo4j: Database connection settings")
    println("  - processing: Import and processing options")
    println("  - yosys: Yosys tool configuration")
    println("  - batchProcessing: Batch processing settings")
    println("  - embeddings: Embedding generation configuration")
    println("")
    println("Note: All processing behavior is driven by the configuration file.")
  }
}

@main def main(args: String*): Unit =
  val logger = Logger("netlist2neo4j")

  val cliConfig = Main.parseArgs(args.toList)

  // 加载配置文件
  val appConfig = cliConfig.configFile match {
    case Some(configPath) =>
      ConfigManager.loadFromFile(configPath) match {
        case Success(config) => config
        case Failure(error) =>
          logger.error(s"Failed to load configuration: ${error.getMessage}")
          sys.exit(1)
      }
    case None =>
      // 使用默认配置
      ConfigManager.createDefault()
  }
  logger.info(
    s"Effective config: clearDatabase=${appConfig.processing.clearDatabase}, " +
      s"importNetlist=${appConfig.processing.importNetlist}, importAST=${appConfig.processing.importAST}, " +
      s"importDFG=${appConfig.processing.importDFG}, alignDFGToAST=${appConfig.processing.alignDFGToAST}, " +
      s"importParallelModules=${appConfig.processing.importParallelModules}, " +
      s"importBatchSize=${appConfig.processing.importBatchSize}, generateEmbeddings=${appConfig.processing.generateEmbeddings}"
  )

  // 确定输入文件
  val inputFile = cliConfig.inputPath

  if (inputFile.isEmpty) {
    logger.error("No input file specified.")
    Main.printUsage()
    sys.exit(1)
  }

  val neo4jUri = sys.env.getOrElse("NEO4J_URI", appConfig.neo4j.uri)
  val neo4jUser = sys.env.getOrElse("NEO4J_USER", appConfig.neo4j.username)
  val neo4jPassword = sys.env.getOrElse("NEO4J_PASSWORD", appConfig.neo4j.password)

  // 创建embedding配置
  val embeddingConfig = ConfigManager.toEmbeddingServiceConfig(appConfig.embeddings)

  // 处理单个文件或文件列表
  val filePath = inputFile.get
  logger.info(s"Processing file: $filePath")

  val isListFile = filePath.endsWith(".txt")
  val batchEnabled = appConfig.batchProcessing.enabled || isListFile

  val result: Try[List[(String, Either[String, (YosysRoot, Option[ASTRoot])])]] =
    if (batchEnabled) {
      // CLI 提供的 .txt 优先，其次使用配置里的 listFile
      val listPathOpt =
        if (isListFile) Some(filePath) else appConfig.batchProcessing.listFile
      val fileListTry = listPathOpt
        .map(Main.readBatchList)
        .getOrElse(Failure(new IllegalArgumentException("Batch mode enabled but no list file specified")))

      fileListTry.flatMap { files =>
        Main.processBatchFiles(files, appConfig.yosys, appConfig.processing.importNetlist, appConfig.batchProcessing.parallel)
      }
    } else if (filePath.endsWith(".v") || filePath.endsWith(".sv") || filePath.endsWith(".vh")) {
      // Verilog文件处理
      Main.processVerilogFile(filePath, appConfig.yosys, appConfig.processing.importNetlist).map { result =>
        List((filePath, Right(result)))
      }
    } else if (filePath.endsWith(".json")) {
      // JSON文件处理
      Main.readJsonFile(filePath).flatMap(Main.parseJsonContent).map { netlist =>
        List((filePath, Right((netlist, None))))
      }
    } else {
      Failure(new IllegalArgumentException(s"Unsupported file type: $filePath"))
    }

  result match {
    case Success(processedFiles) =>
      logger.info(s"Successfully processed ${processedFiles.length} files.")

      Using(GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUser, neo4jPassword))) { driver =>
        val importer = new Neo4jImporter(
          driver,
          embeddingConfig,
          appConfig.processing.importBatchSize,
          appConfig.processing.importParallelModules,
          appConfig.processing.generateEmbeddings
        )

        if (appConfig.processing.clearDatabase) {
          logger.info("Clearing database.")
          val (before, after) = importer.clearDatabase()
          logger.info(s"Database clear result: before=$before, after=$after")
        }
        // 导入前确保索引/约束存在，避免后期 MATCH 退化
        importer.ensureIndexes()

        // 处理每个文件的结果
        var successCount = 0
        var failureCount = 0
        val totalFiles = processedFiles.length
        var totalNetlistModules = 0
        var totalNetlistCells = 0
        var totalNetlistNets = 0
        var totalNetlistSignals = 0
        var totalNetlistPorts = 0
        var totalASTModules = 0
        var totalASTBlocks = 0
        var totalASTSignals = 0
        var totalASTEdges = 0
        var totalDFGNodes = 0
        var totalDFGEdges = 0
        var totalDFGSignalRefs = 0
        var totalDFGBlockAnchors = 0

        processedFiles.zipWithIndex.foreach { case ((filePath, processResult), idx) =>
          val current = idx + 1
          logger.info(s"Import progress $current/$totalFiles: $filePath")
          processResult match {
            case Right((netlist, astRootOpt)) =>
              successCount += 1

              // Import netlist
              if (appConfig.processing.importNetlist) {
                val prunedNetlist =
                  if (appConfig.processing.skipParamod) {
                    val filtered = netlist.modules.filterNot { case (name, _) => name.startsWith("$paramod$") }
                    netlist.copy(modules = filtered)
                  } else netlist

                logger.info(s"Importing netlist from $filePath (modules=${prunedNetlist.modules.size})...")
                val stats = importer.importNetlist(prunedNetlist)
                totalNetlistModules += stats.modules
                totalNetlistCells += stats.cells
                totalNetlistNets += stats.nets
                totalNetlistSignals += stats.signals
                totalNetlistPorts += stats.ports
              }

              // Import AST if available and enabled
              if (appConfig.processing.importAST && astRootOpt.isDefined) {
                val astRoot = astRootOpt.get
                logger.info(s"Importing AST from $filePath (${astRoot.modules.length} modules)...")
                val astStats = importer.importAST(astRoot)
                totalASTModules += astStats.modules
                totalASTBlocks += astStats.blocks
                totalASTSignals += astStats.signals
                totalASTEdges += astStats.edges

                // Import DFG if enabled (requires Verilog source file)
                if (
                  appConfig.processing.importDFG &&
                  (filePath.endsWith(".v") || filePath.endsWith(".sv") || filePath.endsWith(".vh"))
                ) {
                  DFGExtractor.extractFromVerilog(
                    filePath,
                    appConfig.yosys.workDir,
                    appConfig.processing.dfgMaxNodesPerFile,
                    appConfig.processing.dfgIncludeTempNodes
                  ) match {
                    case Success(dfgGraph) =>
                      val dfgStats = importer.importDFG(
                        dfgGraph,
                        astRoot,
                        appConfig.processing.alignDFGToAST
                      )
                      totalDFGNodes += dfgStats.nodes
                      totalDFGEdges += dfgStats.edges
                      totalDFGSignalRefs += dfgStats.signalRefs
                      totalDFGBlockAnchors += dfgStats.blockAnchors
                    case Failure(err) =>
                      logger.warn(s"DFG extraction/import skipped for $filePath: ${err.getMessage}")
                  }
                }

                // Build and import alignment between AST and netlist (if netlist imported)
                if (appConfig.processing.importNetlist) {
                  val alignment = AlignmentBuilder.build(netlist, astRoot)
                  importer.importAlignment(alignment)
                }
              }
            case Left(errMsg) =>
              failureCount += 1
              logger.warn(s"Skip failed file: $filePath - $errMsg")
          }
          Main.printProgress("Import", current, totalFiles)
        }

        // 统计信息
        logger.info(s"Processing completed:")
        logger.info(s"  ✅ Successful: $successCount files")
        logger.info(s"  ❌ Failed: $failureCount files")
        if (appConfig.processing.importNetlist) {
          logger.info("  📋 Netlist import totals:")
          logger.info(s"    Modules: $totalNetlistModules")
          logger.info(s"    Cells: $totalNetlistCells")
          logger.info(s"    Nets: $totalNetlistNets")
          logger.info(s"    Signals: $totalNetlistSignals")
          logger.info(s"    Ports: $totalNetlistPorts")
        }
        if (appConfig.processing.importAST) {
          logger.info("  🌳 AST import totals:")
          logger.info(s"    Modules: $totalASTModules")
          logger.info(s"    Blocks: $totalASTBlocks")
          logger.info(s"    Signals: $totalASTSignals")
          logger.info(s"    Edges: $totalASTEdges")
        }
        if (appConfig.processing.importDFG) {
          logger.info("  🔀 DFG import totals:")
          logger.info(s"    Nodes: $totalDFGNodes")
          logger.info(s"    Edges: $totalDFGEdges")
          logger.info(s"    REFERS_AST_SIGNAL: $totalDFGSignalRefs")
          logger.info(s"    ANCHOR_BLOCK: $totalDFGBlockAnchors")
        }
        if (embeddingConfig.isDefined) {
          logger.info(s"  🔢 Embeddings generated for Module and Block nodes")
        }

      }.recover { case ex =>
        logger.error(s"Error connecting to Neo4j: ${ex.getMessage}", ex)
        sys.exit(1)
      }

    case Failure(error) =>
      logger.error(s"Error processing file: ${error.getMessage}")
      sys.exit(1)
  }

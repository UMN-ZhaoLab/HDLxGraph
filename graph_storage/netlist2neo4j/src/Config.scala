package netlist2neo4j

import io.circe.parser.*
import io.circe.generic.auto.*
import scala.util.{Try, Success, Failure}
import java.io.File
import com.typesafe.scalalogging.Logger

/**
 * 应用程序配置
 */
case class AppConfig(
  neo4j: Neo4jConfig,
  processing: ProcessingConfig,
  yosys: YosysConfig,
  batchProcessing: BatchProcessingConfig,
  embeddings: EmbeddingConfig
)

case class Neo4jConfig(
  uri: String = "bolt://localhost:7687",
  username: String = "neo4j",
  password: String = "neo4j123"
)

case class ProcessingConfig(
  clearDatabase: Boolean = false,
  importNetlist: Boolean = true,
  importAST: Boolean = true,
  importDFG: Boolean = true,
  alignDFGToAST: Boolean = true,
  dfgMaxNodesPerFile: Int = 5000,
  dfgIncludeTempNodes: Boolean = true,
  importParallelModules: Boolean = false,
  importBatchSize: Int = 200,
  importMaxRetries: Int = 3,
  generateEmbeddings: Boolean = false,
  skipParamod: Boolean = false
)

case class YosysConfig(
  workDir: String = ".",
  includeDirs: List[String] = Nil,
  useSystemVerilog: Boolean = true,
  timeoutSeconds: Int = 0,
  maxRetries: Int = 0
)

case class BatchProcessingConfig(
  enabled: Boolean = false,
  listFile: Option[String] = None,
  parallel: Boolean = false
)

case class EmbeddingConfig(
  enabled: Boolean = false,
  provider: String = "local",  // local, ollama, openai, codet5, disabled
  model: Option[String] = None,
  apiKey: Option[String] = None,
  apiUrl: Option[String] = None,
  dimension: Int = 384,
  batchSize: Int = 10
)

object ConfigManager {
  val logger = Logger("netlist2neo4j.ConfigManager")

  /**
   * 从JSON文件加载配置
   */
  def loadFromFile(configPath: String): Try[AppConfig] = {
    Try {
      val configFile = new File(configPath)
      if (!configFile.exists()) {
        throw new IllegalArgumentException(s"Configuration file not found: $configPath")
      }

      val jsonContent = scala.io.Source.fromFile(configFile).mkString

      decode[AppConfig](jsonContent) match {
        case Right(config) =>
          logger.info(s"Successfully loaded configuration from $configPath")
          validateConfig(config)
        case Left(error) =>
          throw new RuntimeException(s"Failed to parse configuration file: $error")
      }
    }
  }

  /**
   * 创建默认配置
   */
  def createDefault(): AppConfig = {
    AppConfig(
      neo4j = Neo4jConfig(),
      processing = ProcessingConfig(),
      yosys = YosysConfig(),
      batchProcessing = BatchProcessingConfig(),
      embeddings = EmbeddingConfig()
    )
  }

  /**
   * 验证配置的有效性
   */
  private def validateConfig(config: AppConfig): AppConfig = {
    // 验证Neo4j配置
    if (config.neo4j.uri.isEmpty) {
      throw new IllegalArgumentException("Neo4j URI cannot be empty")
    }

    // 验证embedding配置
    config.embeddings.provider.toLowerCase match {
      case "openai" =>
        if (config.embeddings.apiKey.isEmpty && sys.env.get("OPENAI_API_KEY").isEmpty) {
          logger.warn("OpenAI embedding provider selected but no API key provided. Set OPENAI_API_KEY environment variable or provide in config.")
        }
      case "ollama" =>
        if (config.embeddings.apiUrl.isEmpty) {
          logger.warn("Ollama embedding provider selected but no API URL provided, using default http://localhost:11434")
        }
      case "codet5" =>
        // 本地 CodeT5+，依赖 python transformers
      case "local" | "disabled" =>
        // 无需验证
      case unknown =>
        logger.warn(s"Unknown embedding provider: $unknown, falling back to local")
        config.copy(embeddings = config.embeddings.copy(provider = "local"))
    }

    // 验证工作目录
    val workDir = new File(config.yosys.workDir)
    if (!workDir.exists()) {
      workDir.mkdirs()
      logger.info(s"Created working directory: ${config.yosys.workDir}")
    }

    config
  }

  /**
   * 将AppConfig转换为EmbeddingService.EmbeddingConfig
   */
  def toEmbeddingServiceConfig(config: EmbeddingConfig): Option[EmbeddingService.EmbeddingConfig] = {
    if (!config.enabled) {
      return None
    }

    val provider = config.provider.toLowerCase match {
      case "ollama" => EmbeddingService.Ollama
      case "openai" => EmbeddingService.OpenAI
      case "codet5" => EmbeddingService.CodeT5Local
      case "local" => EmbeddingService.Local
      case "disabled" => EmbeddingService.Disabled
      case _ =>
        logger.warn(s"Unknown embedding provider: ${config.provider}, using local")
        EmbeddingService.Local
    }

    Some(EmbeddingService.EmbeddingConfig(
      provider = provider,
      model = config.model,
      apiKey = config.apiKey.orElse(sys.env.get("OPENAI_API_KEY")),
      apiUrl = config.apiUrl,
      dimension = config.dimension,
      batchSize = config.batchSize
    ))
  }

  /**
   * 生成示例配置文件
   */
  def generateSampleConfig(): String = {
    """{
  "neo4j": {
    "uri": "bolt://localhost:7687",
    "username": "neo4j",
    "password": "neo4j123"
  },
  "processing": {
    "clearDatabase": false,
    "importNetlist": true,
    "importAST": true,
    "importDFG": true,
    "alignDFGToAST": true,
    "dfgMaxNodesPerFile": 5000,
    "dfgIncludeTempNodes": true,
    "importParallelModules": false,
    "importBatchSize": 200,
    "importMaxRetries": 3,
    "generateEmbeddings": false
  },
  "yosys": {
    "workDir": "./output",
    "timeoutSeconds": 0,
    "maxRetries": 0
  },
  "batchProcessing": {
    "enabled": false,
    "listFile": "verilog_files.txt",
    "parallel": false
  },
  "embeddings": {
    "enabled": false,
    "provider": "local",   // local | ollama | openai | codet5 | disabled
    "model": null,         // codet5: e.g. "Salesforce/codet5p-110m-embedding"
    "apiKey": null,
    "apiUrl": "http://localhost:11434",
    "dimension": 384,
    "batchSize": 10
  }
}"""
  }
}

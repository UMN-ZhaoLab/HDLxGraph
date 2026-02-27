package netlist2neo4j

import com.typesafe.scalalogging.Logger
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.parse
import sttp.client3._
import sttp.client3.circe._
import scala.sys.process._
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.io.File
import scala.concurrent.{Future, ExecutionContext}

object EmbeddingService {
  sealed trait Provider
  case object Local extends Provider      // 兼容 OpenAI 接口的本地服务
  case object Ollama extends Provider     // 调用 Ollama embeddings API
  case object OpenAI extends Provider     // 调用 OpenAI embeddings API
  case object CodeT5Local extends Provider // 本地 CodeT5+ 模型推理
  case object Disabled extends Provider

  case class EmbeddingConfig(
      provider: Provider,
      model: Option[String],
      apiKey: Option[String],
      apiUrl: Option[String],
      dimension: Int,
      batchSize: Int
  )

  private val logger = Logger("EmbeddingService")

  /** 调用 embedding Provider（OpenAI / Ollama / Local 兼容 OpenAI） */
  def embed(
      inputs: Seq[String],
      config: EmbeddingConfig
  )(implicit ec: ExecutionContext): Future[Seq[Array[Float]]] = config.provider match {
    case Disabled =>
      Future.successful(Seq.empty)
    case CodeT5Local =>
      Future(callCodeT5Local(inputs, config))
    case OpenAI =>
      callOpenAICompatible(
        inputs = inputs,
        url = config.apiUrl.getOrElse("https://api.openai.com/v1/embeddings"),
        model = config.model.getOrElse("text-embedding-3-small"),
        apiKey = config.apiKey.orElse(sys.env.get("OPENAI_API_KEY")),
        useApiKey = true
      )
    case Ollama =>
      // Ollama embeddings API: POST /api/embeddings {model, input}
      callOpenAICompatible(
        inputs = inputs,
        url = config.apiUrl.getOrElse("http://localhost:11434/api/embeddings"),
        model = config.model.getOrElse("nomic-embed-text"),
        apiKey = None,
        useApiKey = false
      )
    case Local =>
      // 假设本地也兼容 OpenAI embeddings 接口
      callOpenAICompatible(
        inputs = inputs,
        url = config.apiUrl.getOrElse("http://localhost:11434/api/embeddings"),
        model = config.model.getOrElse("local-embedding"),
        apiKey = None,
        useApiKey = false
      )
  }

  // --- OpenAI 兼容调用 ---
  private def callOpenAICompatible(
      inputs: Seq[String],
      url: String,
      model: String,
      apiKey: Option[String],
      useApiKey: Boolean
  )(implicit ec: ExecutionContext): Future[Seq[Array[Float]]] = {
    if (inputs.isEmpty) return Future.successful(Seq.empty)

    val capped = inputs.map { s =>
      if (s.length > 8000) s.take(8000) else s
    }
    val payload = EmbeddingRequest(model = model, input = capped)
    val baseReq = basicRequest.post(uri"$url").body(payload).response(asJson[EmbeddingResponse])
    val req = if (useApiKey) {
      val key = apiKey.orElse(sys.env.get("OPENAI_API_KEY")).getOrElse("")
      baseReq.header("Authorization", s"Bearer $key")
    } else baseReq

    Future {
      req.send(HttpClientSyncBackend())
    }.map { resp =>
      resp.body match {
        case Left(err) =>
          logger.error(s"Embedding request failed: $err")
          Seq.empty
        case Right(rsp) =>
          rsp.data.map(d => d.embedding.map(_.toFloat).toArray)
      }
    }
  }

  // --- CodeT5+ 本地推理 ---
  private def callCodeT5Local(
      inputs: Seq[String],
      config: EmbeddingConfig
  ): Seq[Array[Float]] = {
    if (inputs.isEmpty) return Seq.empty

    val modelId = config.model.getOrElse("Salesforce/codet5p-110m-embedding")
    val scriptPaths = Seq(
      "graph_storage/netlist2neo4j/scripts/codet5_embed.py",
      "netlist2neo4j/scripts/codet5_embed.py",
      "scripts/codet5_embed.py"
    )

    val scriptFile = scriptPaths.map(new File(_)).find(_.exists())
    if (scriptFile.isEmpty) {
      logger.error("CodeT5 embedding script not found. Expected at graph_storage/netlist2neo4j/scripts/codet5_embed.py")
      return Seq.empty
    }

    val inputFile = Files.createTempFile("codet5_inputs", ".json")
    val outputFile = Files.createTempFile("codet5_outputs", ".json")
    try {
      Files.write(inputFile, inputs.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))

      val cmd = Seq(
        "python3",
        scriptFile.get.getAbsolutePath,
        "--model",
        modelId,
        "--batch-size",
        math.max(1, config.batchSize).toString,
        "--input-file",
        inputFile.toAbsolutePath.toString,
        "--output-file",
        outputFile.toAbsolutePath.toString
      )

      val exitCode = cmd.!
      if (exitCode != 0) {
        logger.error(s"CodeT5 embedding script failed with exit code $exitCode")
        return Seq.empty
      }

      val content = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
      parse(content).flatMap(_.as[Seq[Seq[Double]]]) match {
        case Left(err) =>
          logger.error(s"Failed to parse CodeT5 embedding output: $err")
          Seq.empty
        case Right(values) =>
          values.map(vec => vec.map(_.toFloat).toArray)
      }
    } catch {
      case ex: Exception =>
        logger.error(s"CodeT5 embedding generation failed: ${ex.getMessage}")
        Seq.empty
    } finally {
      Files.deleteIfExists(inputFile)
      Files.deleteIfExists(outputFile)
    }
  }

  // --- Data models for OpenAI-compatible embeddings response ---
  case class EmbeddingRequest(model: String, input: Seq[String])
  case class EmbeddingResponse(data: Seq[EmbeddingData])
  case class EmbeddingData(embedding: Vector[Double])
}

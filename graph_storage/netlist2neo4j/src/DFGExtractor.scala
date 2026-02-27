package netlist2neo4j

import com.typesafe.scalalogging.Logger
import io.circe.generic.auto._
import io.circe.parser.decode

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process._
import scala.util.{Try, Success, Failure}

object DFGExtractor {
  private val logger = Logger("netlist2neo4j.DFGExtractor")

  private def scriptCandidates(repoRoot: File): List[File] = List(
    new File(repoRoot, "graph_storage/netlist2neo4j/scripts/extract_dfg.py"),
    new File(repoRoot, "scripts/extract_dfg.py")
  )

  def extractFromVerilog(
    verilogPath: String,
    workDir: String,
    maxNodes: Int,
    includeTempNodes: Boolean
  ): Try[DFGGraph] = {
    val repoRoot = new File(".").getCanonicalFile
    val scriptOpt = scriptCandidates(repoRoot).find(_.exists())
    if (scriptOpt.isEmpty) {
      return Failure(new RuntimeException("DFG extractor script not found"))
    }

    val outDir = {
      val dir = new File(workDir)
      if (!dir.exists()) dir.mkdirs()
      dir
    }
    val outFile = Files.createTempFile(outDir.toPath, "dfg_", ".json")

    try {
      val cmd = Seq(
        "python3",
        scriptOpt.get.getAbsolutePath,
        "--verilog-file",
        verilogPath,
        "--output-file",
        outFile.toAbsolutePath.toString,
        "--max-nodes",
        math.max(1, maxNodes).toString,
        "--include-temp-nodes",
        includeTempNodes.toString
      )

      val stdout = new StringBuilder
      val stderr = new StringBuilder
      val exit = cmd.!(ProcessLogger(
        line => stdout.append(line).append('\n'),
        line => stderr.append(line).append('\n')
      ))
      if (exit != 0) {
        val errTail = stderr.toString().split('\n').takeRight(30).mkString("\n")
        return Failure(new RuntimeException(s"DFG extraction failed for $verilogPath (exit=$exit):\n$errTail"))
      }

      val content = Files.readString(outFile, StandardCharsets.UTF_8)
      decode[DFGGraph](content) match {
        case Right(graph) => Success(graph)
        case Left(err) =>
          Failure(new RuntimeException(s"Failed to parse DFG JSON: $err"))
      }
    } catch {
      case ex: Exception =>
        logger.warn(s"DFG extraction exception for $verilogPath: ${ex.getMessage}")
        Failure(ex)
    } finally {
      Files.deleteIfExists(outFile)
    }
  }
}


package netlist2neo4j

import com.typesafe.scalalogging.Logger
import scala.sys.process._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.concurrent.duration._
import scala.concurrent.blocking
import scala.util.{Try, Success, Failure}
import java.io.File
import scala.io.Source
import java.nio.file.{Files, StandardOpenOption}

/**
 * Yosys输出模式枚举
 */
sealed trait YosysOutputMode
case object NetlistOutput extends YosysOutputMode
case object ASTOutput extends YosysOutputMode

/**
 * Yosys命令常量
 */
object YosysCommands {
  val READ_VERILOG = "read_verilog"
  val READ_VERILOG_AST = "read_verilog -dump_ast1"
  val HIERARCHY = "hierarchy"
  val PROC = "proc"
  val OPT = "opt"
  val WRITE_JSON = "write_json"
  val DUMP_AST = "dump -write ast_output.txt"
}

/**
 * Yosys网表生成命令配置
 */
case class YosysNetlistCommand(
    readVerilog: String = YosysCommands.READ_VERILOG,
    hierarchy: String = YosysCommands.HIERARCHY,
    proc: String = YosysCommands.PROC,
    opt: String = YosysCommands.OPT,
    writeJson: String = YosysCommands.WRITE_JSON
)

/**
 * Yosys AST生成命令配置
 */
case class YosysASTCommand(
    readVerilog: String = YosysCommands.READ_VERILOG_AST,
    dumpAST: String = YosysCommands.DUMP_AST
)

/**
 * 统一的Yosys命令配置
 */
case class YosysCommand(
    netlistCommands: YosysNetlistCommand = YosysNetlistCommand(),
    astCommands: YosysASTCommand = YosysASTCommand()
)


case class SynthesisResult(
    success: Boolean,
    outputMode: YosysOutputMode,
    jsonPath: Option[String], // Netlist模式才有
    astPath: Option[String],  // AST模式才有
    verilogFiles: List[String],
    logOutput: String,
    errorOutput: String,
    executionTime: Long
)

object YosysIntegration {
  private val logger = Logger("yosys-integration")

  private case class CommandResult(exitCode: Int, timedOut: Boolean)

  private def runProcessWithTimeout(
      processBuilder: ProcessBuilder,
      processLogger: ProcessLogger,
      timeoutSeconds: Int
  ): CommandResult = {
    if (timeoutSeconds <= 0) {
      return CommandResult(processBuilder.!(processLogger), timedOut = false)
    }

    val process = processBuilder.run(processLogger)
    val exitFuture = Future(blocking(process.exitValue()))(ExecutionContext.global)
    try {
      val exitCode = Await.result(exitFuture, timeoutSeconds.seconds)
      CommandResult(exitCode, timedOut = false)
    } catch {
      case _: TimeoutException =>
        process.destroy()
        CommandResult(exitCode = -1, timedOut = true)
    }
  }

  /**
   * 执行Yosys综合流程（网表模式）
   */
  def synthesizeVerilogToJSON(
      verilogFiles: List[String],
      config: YosysConfig = YosysConfig()
  ): Try[SynthesisResult] = {
    synthesizeVerilog(verilogFiles, NetlistOutput, config)
  }

  /**
   * 执行简单的AST提取（直接命令，无需脚本）
   */
  def extractVerilogASTSimple(
      verilogFile: String,
      workDir: File,
      yosysExecutable: String = "yosys",
      config: YosysConfig = YosysConfig()
  ): Try[String] = {
    logger.info(s"Extracting AST from Verilog file: $verilogFile")

    val fileName = new File(verilogFile).getName
    val pathHash = Integer.toHexString(new File(verilogFile).getCanonicalPath.hashCode)
    val astFile = new File(workDir, s"${fileName.replaceAll("[.]", "_")}_${pathHash}_ast.txt")

    try {
      val svFlag = if (config.useSystemVerilog) " -sv" else ""
      val incFlags = config.includeDirs.map(d => s"-I$d").mkString(" ")
      val command = s"$yosysExecutable -p \"${YosysCommands.READ_VERILOG_AST}$svFlag $incFlags $verilogFile\""
      val maxRetries = math.max(0, config.maxRetries)
      val maxAttempts = maxRetries + 1
      val timeoutSeconds = math.max(0, config.timeoutSeconds)
      var attempt = 1

      while (attempt <= maxAttempts) {
        // 先清空旧文件，避免多次运行时追加导致重复内容
        if (astFile.exists()) {
          astFile.delete()
        }
        val processLogger = ProcessLogger(
          stdout => {
            import java.nio.file.{Files, StandardOpenOption}
            Files.write(astFile.toPath, (stdout + "\n").getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
          },
          stderr => logger.error(s"Yosys AST stderr: $stderr")
        )

        val result = runProcessWithTimeout(Process(command), processLogger, timeoutSeconds)

        if (!result.timedOut && result.exitCode == 0 && astFile.exists() && astFile.length() > 0) {
          logger.info(s"AST extracted successfully to: ${astFile.getAbsolutePath}")
          return Success(astFile.getAbsolutePath)
        }

        if (result.timedOut && attempt < maxAttempts) {
          logger.warn(s"Yosys AST timed out after ${timeoutSeconds}s (attempt $attempt/$maxAttempts), retrying: $verilogFile")
          attempt += 1
        } else {
          val reason = if (result.timedOut) s"timeout after ${timeoutSeconds}s" else s"exit code ${result.exitCode}"
          logger.error(s"AST extraction failed ($reason) for $verilogFile")
          return Failure(new Exception(s"AST extraction failed ($reason)"))
        }
      }
      Failure(new Exception("AST extraction failed - retries exhausted"))
    } catch {
      case ex: Exception =>
        logger.error("Error during AST extraction", ex)
        Failure(ex)
    }
  }

  /**
   * 通用的Yosys处理流程
   */
  private def synthesizeVerilog(
      verilogFiles: List[String],
      outputMode: YosysOutputMode,
      config: YosysConfig
  ): Try[SynthesisResult] = {
    val startTime = System.currentTimeMillis()

    val modeStr = outputMode match {
      case NetlistOutput => "netlist synthesis"
      case ASTOutput => "AST extraction"
    }

    logger.info(s"Starting Yosys $modeStr for ${verilogFiles.size} files")
    logger.info(s"Verilog files: ${verilogFiles.mkString(", ")}")

    // 验证输入文件
    val validation = validateVerilogFiles(verilogFiles)
    if (validation.isFailure) {
      return validation.flatMap(_ => Failure(new Exception("File validation failed")))
    }

    // 生成Yosys脚本
    val script = outputMode match {
      case NetlistOutput =>
        generateNetlistScript(verilogFiles, YosysNetlistCommand(), new File(config.workDir), config)
      case ASTOutput =>
        generateASTScript(verilogFiles, YosysASTCommand(), config)
    }
    logger.debug(s"Generated Yosys script:\n$script")

    // 创建临时脚本文件
    val scriptFile = File.createTempFile("yosys_script_", ".ys", new File(config.workDir))
    try {
      // 写入脚本文件
      import java.nio.file.{Files, StandardOpenOption}
      Files.write(scriptFile.toPath, script.getBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

      // 清理旧的 netlist 输出，避免读取到上次残留文件
      if (outputMode == NetlistOutput) {
        val jsonFile = new File(config.workDir, "netlist.json")
        if (jsonFile.exists()) {
          jsonFile.delete()
        }
      }

      // 构建执行命令
      val command = buildYosysCommand(scriptFile, config)
      logger.info(s"Executing Yosys command: ${command.mkString(" ")}")

      // 执行命令
      val result = executeYosysCommand(command, None, config)

      // 检查输出文件
      val (jsonPath, astPath) = outputMode match {
        case NetlistOutput =>
          val workDirFile = new File(config.workDir)
          val jsonFile = new File(workDirFile, "netlist.json")
          if (!jsonFile.exists()) {
            logger.error("JSON netlist file was not generated")
            logger.error(f"Work directory: ${workDirFile.getAbsolutePath}")
            logger.error(f"Script execution exit code: ${result.exitCode}")
            if (result.stderr.nonEmpty) {
              logger.error(s"Yosys stderr: ${result.stderr}")
            }
            // 保留脚本文件用于调试
            val debugScriptFile = new File(workDirFile, "debug_script.ys")
            Files.write(debugScriptFile.toPath, script.getBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            logger.info(s"Debug script saved to: ${debugScriptFile.getAbsolutePath}")
            return Failure(new Exception("JSON netlist file was not generated"))
          }
          if (result.exitCode != 0) {
            return Failure(new Exception(s"Yosys failed with exit code ${result.exitCode}"))
          }
          (Some(jsonFile.getAbsolutePath), None)

        case ASTOutput =>
          val workDirFile = new File(config.workDir)
          val astFile = new File(workDirFile, "ast_output.txt")
          if (!astFile.exists()) {
            logger.error("AST output file was not generated")
            return Failure(new Exception("AST output file was not generated"))
          }
          (None, Some(astFile.getAbsolutePath))
      }

      val executionTime = System.currentTimeMillis() - startTime
      val synthesisResult = SynthesisResult(
        success = true,
        outputMode = outputMode,
        jsonPath = jsonPath,
        astPath = astPath,
        verilogFiles = verilogFiles,
        logOutput = result.stdout,
        errorOutput = result.stderr,
        executionTime = executionTime
      )

      logger.info(s"Yosys $modeStr completed successfully in ${executionTime}ms")
      jsonPath.foreach(path => logger.info(s"Generated JSON file: $path"))
      astPath.foreach(path => logger.info(s"Generated AST file: $path"))

      Success(synthesisResult)

    } catch {
      case ex: Exception =>
        val executionTime = System.currentTimeMillis() - startTime
        logger.error(s"Yosys $modeStr failed after ${executionTime}ms", ex)
        Failure(ex)
    } finally {
      // 清理临时文件
      if (scriptFile.exists()) {
        scriptFile.delete()
      }
    }
  }

  /**
   * 验证Verilog文件是否存在且可读
   */
  private def validateVerilogFiles(files: List[String]): Try[Unit] = {
    val invalidFiles = files.filter { file =>
      val f = new File(file)
      !f.exists() || !f.canRead
    }

    if (invalidFiles.nonEmpty) {
      logger.error(s"Invalid Verilog files: ${invalidFiles.mkString(", ")}")
      Failure(new Exception(s"Invalid Verilog files: ${invalidFiles.mkString(", ")}"))
    } else {
      Success(())
    }
  }

  /**
   * 生成网表Yosys脚本
   */
  private def generateNetlistScript(
      verilogFiles: List[String],
      commands: YosysNetlistCommand,
      workDir: File,
      config: YosysConfig
  ): String = {
    val script = new StringBuilder()

    val svFlag = if (config.useSystemVerilog) " -sv" else ""
    val incFlags = config.includeDirs.map(d => s"-I$d").mkString(" ")
    val absFiles = verilogFiles.map(f => new File(f).getAbsolutePath).mkString(" ")
    script.append(s"${commands.readVerilog}$svFlag $incFlags $absFiles\n")

    // 综合流程
    script.append(s"${commands.hierarchy}\n")
    script.append(s"${commands.proc}\n")
    script.append(s"${commands.opt}\n")

    // 输出JSON - 使用绝对路径
    val jsonPath = new File(workDir, "netlist.json").getAbsolutePath
    script.append(s"${commands.writeJson} $jsonPath\n")

    script.toString()
  }

  /**
   * 生成AST Yosys脚本
   */
  private def generateASTScript(
      verilogFiles: List[String],
      commands: YosysASTCommand,
      config: YosysConfig
  ): String = {
    val script = new StringBuilder()

    val svFlag = if (config.useSystemVerilog) " -sv" else ""
    val incFlags = config.includeDirs.map(d => s"-I$d").mkString(" ")
    val absFiles = verilogFiles.map(f => new File(f).getAbsolutePath).mkString(" ")
    script.append(s"${commands.readVerilog}$svFlag $incFlags $absFiles\n")

    // 输出AST
    script.append(s"${commands.dumpAST}\n")

    script.toString()
  }

  /**
   * 构建Yosys执行命令
   */
  private def buildYosysCommand(
      scriptFile: File,
      config: YosysConfig
  ): List[String] = {
    val baseCommand = List("yosys", "-q", scriptFile.getAbsolutePath)

    baseCommand
  }

  /**
   * 执行Yosys命令
   */
  private def executeYosysCommand(
      command: Seq[String],
      environment: Option[String],
      config: YosysConfig
  ): ProcessOutput = {
    val maxRetries = math.max(0, config.maxRetries)
    val maxAttempts = maxRetries + 1
    val timeoutSeconds = math.max(0, config.timeoutSeconds)
    var attempt = 1
    var lastOutput = ProcessOutput("", "", -1)

    while (attempt <= maxAttempts) {
      val stdoutBuffer = new StringBuilder
      val stderrBuffer = new StringBuilder

      val processLogger = ProcessLogger(
        stdout => {
          logger.debug(s"Yosys stdout: $stdout")
          stdoutBuffer.append(stdout).append("\n")
        },
        stderr => {
          logger.error(s"Yosys stderr: $stderr")
          stderrBuffer.append(stderr).append("\n")
        }
      )

      val result = runProcessWithTimeout(Process(command), processLogger, timeoutSeconds)
      val exitCode = result.exitCode

      if (result.timedOut && attempt < maxAttempts) {
        logger.warn(s"Yosys timed out after ${timeoutSeconds}s (attempt $attempt/$maxAttempts), retrying.")
        attempt += 1
        lastOutput = ProcessOutput(stdoutBuffer.toString(), stderrBuffer.toString(), exitCode)
      } else {
        if (exitCode != 0) {
          logger.error(s"Yosys process exited with code $exitCode")
        }
        lastOutput = ProcessOutput(stdoutBuffer.toString(), stderrBuffer.toString(), exitCode)
        return lastOutput
      }
    }

    lastOutput
  }

  /**
   * 创建高级网表综合脚本
   */
  def createAdvancedNetlistScript(
      verilogFiles: List[String],
      topModule: Option[String] = None,
      additionalCommands: List[String] = Nil
  ): String = {
    val script = new StringBuilder()

    // 读取Verilog文件
    verilogFiles.foreach { file =>
      script.append(s"read_verilog $file\n")
    }

    // 层次化处理（可选指定顶层模块）
    topModule.foreach { module =>
      script.append(s"hierarchy -top $module\n")
    }

    // 处理流程
    script.append("proc\n")
    script.append("opt\n")
    script.append("techmap\n")
    script.append("opt\n")

    // 添加额外的命令
    additionalCommands.foreach { cmd =>
      script.append(s"$cmd\n")
    }

    // 输出JSON
    script.append("write_json netlist.json\n")

    script.toString()
  }

  /**
   * 创建AST提取脚本
   */
  def createASTScript(
      verilogFiles: List[String]
  ): String = {
    val script = new StringBuilder()

    // 读取Verilog文件并输出AST
    verilogFiles.foreach { file =>
      script.append(s"read_verilog -dump_ast1 $file\n")
    }

    // 输出AST到文件
    script.append("dump -write ast_output.txt\n")

    script.toString()
  }

  /**
   * 从Yosys输出中提取统计信息
   */
  def extractStatistics(logOutput: String): Map[String, String] = {
    val stats = scala.collection.mutable.Map[String, String]()

    // 提取模块数量
    val modulePattern = """Number of modules:\s*(\d+)""".r
    modulePattern.findFirstMatchIn(logOutput).foreach { m =>
      stats += "modules" -> m.group(1)
    }

    // 提取单元数量
    val cellPattern = """Number of cells:\s*(\d+)""".r
    cellPattern.findFirstMatchIn(logOutput).foreach { m =>
      stats += "cells" -> m.group(1)
    }

    // 提取网络数量
    val netPattern = """Number of nets:\s*(\d+)""".r
    netPattern.findFirstMatchIn(logOutput).foreach { m =>
      stats += "nets" -> m.group(1)
    }

    stats.toMap
  }
}

/**
 * 进程输出封装
 */
case class ProcessOutput(
    stdout: String,
    stderr: String,
    exitCode: Int
)

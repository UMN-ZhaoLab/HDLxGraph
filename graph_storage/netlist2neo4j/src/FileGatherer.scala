package netlist2neo4j

import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.util.{Try, Success, Failure}
import scala.io.Source
import com.typesafe.scalalogging.Logger

/**
 * 批量文件收集和处理工具
 * 类似HDLxGraph的gather.py功能
 */
object FileGatherer {
  val logger = Logger("netlist2neo4j.FileGatherer")

  // 支持的Verilog文件扩展名
  val VerilogExtensions = Set(".v", ".sv", ".vh", ".svh")

  case class GatherConfig(
    sourceDir: String,
    targetDir: String,
    recursive: Boolean = true,
    includePattern: Option[String] = None,
    excludePattern: Option[String] = None
  )

  case class FileInfo(
    path: String,
    relativePath: String,
    size: Long,
    lastModified: Long
  )

  /**
   * 收集目录中的所有Verilog文件
   */
  def gatherVerilogFiles(config: GatherConfig): Try[List[FileInfo]] = {
    Try {
      val sourcePath = Paths.get(config.sourceDir)
      val targetPath = Paths.get(config.targetDir)

      if (!Files.exists(sourcePath)) {
        throw new IllegalArgumentException(s"Source directory does not exist: ${config.sourceDir}")
      }

      // 创建目标目录
      Files.createDirectories(targetPath)

      val files = collectFiles(sourcePath, config)
      logger.info(s"Found ${files.length} Verilog files")

      // 复制文件到目标目录
      val copiedFiles = files.map { fileInfo =>
        copyFileToTarget(fileInfo, sourcePath, targetPath)
      }

      copiedFiles
    }
  }

  /**
   * 递归收集文件
   */
  private def collectFiles(
    basePath: Path,
    config: GatherConfig
  ): List[FileInfo] = {
    val files = mutable.ListBuffer[FileInfo]()

    val baseDir = if (config.recursive) basePath else basePath

    def walkDirectory(path: Path): Unit = {
      if (Files.isDirectory(path)) {
        Files.list(path).forEach { childPath =>
          if (Files.isDirectory(childPath) && config.recursive) {
            walkDirectory(childPath)
          } else if (Files.isRegularFile(childPath)) {
            processFile(childPath, basePath)
          }
        }
      }
    }

    def processFile(filePath: Path, basePath: Path): Unit = {
      val fileName = filePath.getFileName.toString

      // 检查文件扩展名
      val extension = getFileExtension(fileName)
      if (VerilogExtensions.contains(extension)) {

        // 检查包含/排除模式
        val shouldInclude = checkPatterns(fileName, config.includePattern, config.excludePattern)

        if (shouldInclude) {
          val relativePath = basePath.relativize(filePath).toString
          val size = Files.size(filePath)
          val lastModified = Files.getLastModifiedTime(filePath).toMillis

          files += FileInfo(
            path = filePath.toString,
            relativePath = relativePath,
            size = size,
            lastModified = lastModified
          )

          logger.debug(s"Found file: $relativePath")
        }
      }
    }

    walkDirectory(baseDir)
    files.toList
  }

  /**
   * 复制文件到目标目录
   */
  private def copyFileToTarget(
    fileInfo: FileInfo,
    sourceBasePath: Path,
    targetPath: Path
  ): FileInfo = {
    val sourceFile = Paths.get(fileInfo.path)
    val targetFile = targetPath.resolve(sourceFile.getFileName.toString)

    try {
      Files.copy(sourceFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      logger.debug(s"Copied: ${fileInfo.relativePath} -> ${targetFile.getFileName}")
    } catch {
      case ex: Exception =>
        logger.warn(s"Failed to copy ${fileInfo.relativePath}: ${ex.getMessage}")
    }

    fileInfo.copy(path = targetFile.toString)
  }

  /**
   * 检查文件是否匹配包含/排除模式
   */
  private def checkPatterns(
    fileName: String,
    includePattern: Option[String],
    excludePattern: Option[String]
  ): Boolean = {
    val includeMatches = includePattern match {
      case Some(pattern) => fileName.matches(pattern)
      case None => true
    }

    val excludeMatches = excludePattern match {
      case Some(pattern) => fileName.matches(pattern)
      case None => false
    }

    includeMatches && !excludeMatches
  }

  /**
   * 获取文件扩展名
   */
  private def getFileExtension(fileName: String): String = {
    val lastDotIndex = fileName.lastIndexOf('.')
    if (lastDotIndex > 0) {
      fileName.substring(lastDotIndex).toLowerCase
    } else {
      ""
    }
  }

  /**
   * 分析文件依赖关系（简单的include解析）
   */
  def analyzeDependencies(files: List[FileInfo]): Map[String, List[String]] = {
    val dependencies = mutable.Map[String, List[String]]()

    files.foreach { file =>
      val includes = extractIncludes(file.path)
      dependencies(file.path) = includes
    }

    dependencies.toMap
  }

  /**
   * 从Verilog文件中提取include语句
   */
  private def extractIncludes(filePath: String): List[String] = {
    try {
      val source = Source.fromFile(filePath)
      val lines = source.getLines().toList
      source.close()

      val includePattern = """^\s*`include\s+"([^"]+)"""".r

      lines.flatMap {
        case includePattern(fileName) => Some(fileName)
        case _ => None
      }
    } catch {
      case ex: Exception =>
        logger.warn(s"Failed to parse includes from $filePath: ${ex.getMessage}")
        List.empty
    }
  }

  /**
   * 生成批处理报告
   */
  def generateReport(files: List[FileInfo]): String = {
    val totalSize = files.map(_.size).sum
    val oldestFile = files.minBy(_.lastModified)
    val newestFile = files.maxBy(_.lastModified)

    s"""
       |Verilog File Collection Report
       |===============================
       |Total files: ${files.length}
       |Total size: ${formatSize(totalSize)}
       |Oldest file: ${oldestFile.relativePath} (${formatTimestamp(oldestFile.lastModified)})
       |Newest file: ${newestFile.relativePath} (${formatTimestamp(newestFile.lastModified)})
       |
       |File List:
       |${files.map(f => s"  ${f.relativePath} (${formatSize(f.size)})").mkString("\n")}
       |""".stripMargin
  }

  private def formatSize(bytes: Long): String = {
    val kb = bytes / 1024.0
    if (kb < 1024) f"${kb}%.1f KB"
    else {
      val mb = kb / 1024.0
      f"${mb}%.1f MB"
    }
  }

  private def formatTimestamp(timestamp: Long): String = {
    val date = new java.util.Date(timestamp)
    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date)
  }
}
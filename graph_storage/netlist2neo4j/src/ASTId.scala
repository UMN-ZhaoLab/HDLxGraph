package netlist2neo4j

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object ASTId {
  private val ModuleRefPrefix = "ast_module_ref::"

  def moduleRefId(name: String): String = s"$ModuleRefPrefix$name"
  def isModuleRef(id: String): Boolean = id.startsWith(ModuleRefPrefix)
  def moduleRefName(id: String): String = id.stripPrefix(ModuleRefPrefix)

  private def normalizeFile(file: Option[String]): Option[String] =
    file.map(_.replace('\\', '/'))

  private def moduleKey(name: String, file: Option[String], memoryAddress: Option[String]): String = {
    val scope = normalizeFile(file).filter(_.nonEmpty).orElse(memoryAddress).getOrElse("unknown")
    s"$scope::$name"
  }

  private def sha1Hex(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8))
    digest.map("%02x".format(_)).mkString
  }

  def moduleId(name: String, file: Option[String], memoryAddress: Option[String]): String = {
    val suffix = sha1Hex(moduleKey(name, file, memoryAddress))
    s"ast_module_${name}_$suffix"
  }

  def moduleId(module: ASTModuleNode): String =
    moduleId(
      module.name,
      module.sourceRange.map(_.file).orElse(module.attributes.sourcePosition.file),
      module.attributes.memoryAddress
    )

  def portId(ownerId: String, portName: String): String =
    s"$ownerId::port::$portName"

  def signalId(ownerId: String, signalName: String): String =
    s"$ownerId::signal::$signalName"

  def blockId(ownerId: String, blockName: String): String =
    s"$ownerId::block::$blockName"
}

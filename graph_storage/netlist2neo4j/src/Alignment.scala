package netlist2neo4j

import java.io.File

case class ModuleAlignment(astModuleId: String, moduleName: String)
case class SignalAlignment(moduleName: String, netName: String, astSignalId: String)
case class NetBlockAlignment(moduleName: String, netName: String, astBlockId: String)
case class CellBlockAlignment(cellId: String, astBlockId: String)
case class AlignmentData(
    moduleLinks: Seq[ModuleAlignment],
    signalLinks: Seq[SignalAlignment],
    netBlockLinks: Seq[NetBlockAlignment],
    cellBlockLinks: Seq[CellBlockAlignment]
)

object AlignmentBuilder {
  private def normName(name: String) = name.stripPrefix("\\")
  private def moduleId(module: ASTModuleNode) = ASTId.moduleId(module)
  private def portId(module: ASTModuleNode, port: String) = ASTId.portId(moduleId(module), port)
  private def signalId(module: ASTModuleNode, sig: String) = ASTId.signalId(moduleId(module), sig)
  private def blockId(module: ASTModuleNode, blockName: String) = ASTId.blockId(moduleId(module), blockName)

  private def basename(p: String): String = new File(p).getName

  private case class NetSrc(file: String, line: Int)

  private def parseNetSrc(attr: Map[String, String]): Option[NetSrc] = {
    // src 格式通常为 "file:line.col-line.col" 或 "file:line"
    attr.get("src").flatMap { src =>
      val first = src.split("\\|").headOption.getOrElse(src)
      val parts = first.split(":")
      if (parts.length >= 2) {
        val file = parts.dropRight(1).mkString(":")
        val lineStr = parts.last.takeWhile(c => c.isDigit)
        lineStr.toIntOption.map(line => NetSrc(file, line))
      } else None
    }
  }

  def build(netlist: YosysRoot, astRoot: ASTRoot): AlignmentData = {
    val astModulesByNorm: Map[String, ASTModuleNode] =
      astRoot.modules.map(m => normName(m.name) -> m).toMap
    val netModulesByNorm: Map[String, (String, YosysModule)] =
      netlist.modules.map { case (name, mod) => normName(name) -> (name, mod) }.toMap

    val moduleNames = netModulesByNorm.keySet.intersect(astModulesByNorm.keySet)

    val moduleLinks = moduleNames.toSeq.map { n =>
      val astMod = astModulesByNorm(n)
      val (netOrigName, _) = netModulesByNorm(n)
      ModuleAlignment(moduleId(astMod), netOrigName)
    }

    val signalLinks = scala.collection.mutable.ListBuffer[SignalAlignment]()
    val matchedNetNames = scala.collection.mutable.Set[(String, String)]() // (module, netName)

    val netBlockLinks = scala.collection.mutable.ListBuffer[NetBlockAlignment]()
    val cellBlockLinks = scala.collection.mutable.ListBuffer[CellBlockAlignment]()

    moduleNames.foreach { mName =>
      val (netOrigName, netMod) = netModulesByNorm(mName)
      val astMod = astModulesByNorm(mName)

      val netNamesByNorm: Map[String, String] = netMod.netnames.keySet.map(n => normName(n) -> n).toMap

      val astSignals =
        astMod.ports.map(p => (p.name, true)) ++ astMod.internalSignals.map(s => (s.name, false))

      astSignals.foreach { case (sigName, isPort) =>
        val normSig = normName(sigName)
        if (netNamesByNorm.contains(normSig)) {
          val netNameForDb = netNamesByNorm(normSig)
          val astId = if (isPort) portId(astMod, sigName) else signalId(astMod, sigName)
          signalLinks += SignalAlignment(netOrigName, netNameForDb, astId)
          matchedNetNames += ((netOrigName, netNameForDb))
        }
      }

      // 未匹配的 net，按 src 行号关联 block
      val astBlocks = astMod.blocks.flatMap { b =>
        b.sourceRange.map(r =>
          (
            basename(r.file),
            r.startLine,
            r.endLine,
            blockId(astMod, b.name.getOrElse(s"${b.blockType}_${r.startLine}"))
          )
        )
      }
      val astBlockFileGroups = astBlocks.groupBy(_._1) // basename -> list

      netMod.netnames.foreach { case (netName, net) =>
        if (!matchedNetNames.contains((netOrigName, netName))) {
          parseNetSrc(net.attributes).foreach { ns =>
            val base = basename(ns.file)
            astBlockFileGroups.get(base).foreach { blocks =>
              blocks.filter { case (_, start, end, _) =>
                ns.line >= start && ns.line <= end
              }.foreach { case (_, _, _, blkId) =>
                netBlockLinks += NetBlockAlignment(netOrigName, netName, blkId)
              }
            }
          }
        }
      }

      // Cell -> Block 对齐（按 src 行号落点）
      netMod.cells.foreach { case (cellName, cell) =>
        val cellId = s"$netOrigName.$cellName"
        parseNetSrc(cell.attributes).foreach { ns =>
          val base = basename(ns.file)
          astBlockFileGroups.get(base).foreach { blocks =>
            blocks.filter { case (_, start, end, _) =>
              ns.line >= start && ns.line <= end
            }.foreach { case (_, _, _, blkId) =>
              cellBlockLinks += CellBlockAlignment(cellId, blkId)
            }
          }
        }
      }
    }

    AlignmentData(
      moduleLinks.toSeq,
      signalLinks.toSeq,
      netBlockLinks.toSeq,
      cellBlockLinks.toSeq
    )
  }
}

package netlist2neo4j

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import com.typesafe.scalalogging.Logger

class FlatModule(val moduleName: String, val yosysModule: YosysModule) {
  val logger: Logger = Logger[FlatModule]

  val nodes: ListBuffer[Cell] = ListBuffer()

  val wires: ListBuffer[Wire] = ListBuffer()

  private val ridersByNet: mutable.Map[String, ListBuffer[Port]] = mutable.Map()
  private val driversByNet: mutable.Map[String, ListBuffer[Port]] =
    mutable.Map()
  private val lateralsByNet: mutable.Map[String, ListBuffer[Port]] =
    mutable.Map()

  initializeNodes()

  private def initializeNodes(): Unit = {
    yosysModule.ports.foreach { case (portName, yosysPort) =>
      val cell = Cell.fromPort(portName, yosysPort)
      nodes += cell
    }

    yosysModule.cells.foreach { case (cellName, yosysCell) =>
      val cell = Cell.fromYosysCell(cellName, yosysCell)
      nodes += cell
    }

    logger.info(
      s"Initialized FlatModule '$moduleName' with ${nodes.size} nodes."
    )
  }

  def createWires(): Unit = {
    wires.clear()
    ridersByNet.clear()
    driversByNet.clear()
    lateralsByNet.clear()

    nodes.foreach(node =>
      node.collectPortsByDirection(ridersByNet, driversByNet, lateralsByNet)
    )

    val allNetNames =
      (ridersByNet.keySet ++ driversByNet.keySet ++ lateralsByNet.keySet).toList

    allNetNames.foreach { netName =>
      val wire = Wire(netName)
      ridersByNet.get(netName).foreach(ports => wire.riders ++= ports)
      driversByNet.get(netName).foreach(ports => wire.drivers ++= ports)
      lateralsByNet.get(netName).foreach(ports => wire.laterals ++= ports)

      wires += wire

      wire.drivers.foreach(_.wire = Some(wire))
      wire.riders.foreach(_.wire = Some(wire))
      wire.laterals.foreach(_.wire = Some(wire))
    }

    logger.info(s"Created ${wires.size} wires for module '$moduleName'.")
  }
}

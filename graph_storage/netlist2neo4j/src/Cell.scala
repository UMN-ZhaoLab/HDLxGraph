package netlist2neo4j

import io.circe.Json
import scala.collection.mutable.ListBuffer
import scala.collection.mutable

case class Cell(
    key: String,
    cellType: String,
    inputPorts: ListBuffer[Port],
    outputPorts: ListBuffer[Port],
    attributes: Map[String, String] = Map()
) {
  inputPorts.foreach(_.parentNode = Some(this))
  outputPorts.foreach(_.parentNode = Some(this))

  def isPortCell: Boolean =
    cellType == "$_inputExt_" || cellType == "$_outputExt_"

  def collectPortsByDirection(
      ridersByNet: mutable.Map[String, ListBuffer[Port]],
      driversByNet: mutable.Map[String, ListBuffer[Port]],
      lateralsByNet: mutable.Map[String, ListBuffer[Port]]
  ): Unit = {
    inputPorts.foreach { port =>
      val netName = port.valString()
      ridersByNet.getOrElseUpdate(netName, ListBuffer()) += port
    }
    outputPorts.foreach { port =>
      val netName = port.valString()
      driversByNet.getOrElseUpdate(netName, ListBuffer()) += port
    }
  }
}

object Cell {
  def fromPort(name: String, yosysPort: YosysPort): Cell = {
    val port = Port("Y", yosysPort.bits.map {
      case Left(i) => Json.fromInt(i)
      case Right(s) => Json.fromString(s.toString)
    })
    val cellType =
      if (yosysPort.direction == "input") "$_inputExt_" else "$_outputExt_"
    val inputPorts =
      if (cellType == "$_inputExt_") ListBuffer() else ListBuffer(port)
    val outputPorts =
      if (cellType == "$_inputExt_") ListBuffer(port) else ListBuffer()
    Cell(name, cellType, inputPorts, outputPorts)
  }

  def fromYosysCell(name: String, yosysCell: YosysCell): Cell = {
    val inputPorts = ListBuffer[Port]()
    val outputPorts = ListBuffer[Port]()

    yosysCell.connections.foreach { case (portName, bits) =>
      val port = Port(portName, bits)
      val direction =
        yosysCell.port_directions.getOrElse(portName, "inout")
      direction match {
        case "input"  => inputPorts += port
        case "output" => outputPorts += port
        case "inout" =>
          inputPorts += port
          outputPorts += port
      }
    }
    Cell(
      name,
      yosysCell.cell_type,
      inputPorts,
      outputPorts,
      yosysCell.attributes
    )
  }
}

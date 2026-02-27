package netlist2neo4j

import scala.collection.mutable.ListBuffer

case class Wire(
    netName: String,
    drivers: ListBuffer[Port] = ListBuffer(),
    riders: ListBuffer[Port] = ListBuffer(),
    laterals: ListBuffer[Port] = ListBuffer()
) {
  def addDriver(port: Port): Unit = drivers += port
  def addRider(port: Port): Unit = riders += port
  def addLateral(port: Port): Unit = laterals += port
}

package netlist2neo4j

import io.circe.Json

case class Port(
    key: String,
    value: List[Json]
) {
  var parentNode: Option[Cell] = None
  var wire: Option[Wire] = None

  def valString(): String = {
    val stringValues = value.map {
      case json if json.isNumber => json.asNumber.get.toString
      case json if json.isString => json.asString.get
      case other             => other.toString
    }
    "," + stringValues.mkString(",") + ","
  }
}

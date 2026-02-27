package netlist2neo4j

import io.circe._
import io.circe.syntax._

type Signal = Either[Int, String]
type Signals = List[Signal]

case class YosysRoot(creator: String, modules: Map[String, YosysModule])

case class YosysModule(
    attributes: Map[String, String],
    ports: Map[String, YosysPort],
    cells: Map[String, YosysCell],
    netnames: Map[String, YosysNetname]
)

case class YosysPort(
    direction: String,
    bits: Signals
)

case class YosysCell(
    hide_name: Boolean,
    cell_type: String,
    parameters: Map[String, String],
    attributes: Map[String, String],
    port_directions: Map[String, String],
    connections: Map[String, List[Json]]
)

case class YosysNetname(
    hide_name: Int,
    bits: Signals,
    attributes: Map[String, String]
)

// Custom decoders for Yosys JSON format
implicit val signalDecoder: Decoder[Signal] = Decoder.instance { h =>
  h.as[Int].map(Left(_)).orElse(h.as[String].map(Right(_)))
}

implicit val signalsDecoder: Decoder[Signals] = Decoder.instance { c =>
  // First try to decode as List[Int] (Yosys simple format like [2, 3])
  c.as[List[Int]].map(_.map(Left(_))).orElse(
    // Then try List[String]
    c.as[List[String]].map(_.map(Right(_)))
  ).orElse(
    // Finally try List[Json] for more complex cases
    c.as[List[Json]].flatMap { jsonList =>
      Right(jsonList.map { json =>
        // Handle both direct numbers and objects with value field
        json.asNumber.flatMap(_.toInt).map(Left(_)).orElse(
          json.asString.map(Right(_))
        ).orElse(
          // Try to extract from object format like {"value": 2}
          json.asObject.flatMap(_.apply("value")).flatMap(_.asNumber).flatMap(_.toInt).map(Left(_))
        ).getOrElse(Left(0))
      })
    }
  )
}

implicit val yosysPortDecoder: Decoder[YosysPort] = Decoder.instance { c =>
  for {
    direction <- c.downField("direction").as[String]
    bits <- c.downField("bits").as[Signals](signalsDecoder)
  } yield YosysPort(direction, bits)
}

implicit val yosysNetnameDecoder: Decoder[YosysNetname] = Decoder.instance { c =>
  for {
    hide_name <- c.downField("hide_name").as[Int]
    bits <- c.downField("bits").as[Signals](signalsDecoder)
    attributes <- c.downField("attributes").as[Map[String, String]]
  } yield YosysNetname(hide_name, bits, attributes)
}

implicit val yosysCellDecoder: Decoder[YosysCell] = Decoder.instance { c =>
  for {
    hide_name <- c.downField("hide_name").as[Int].map(_ != 0) // Convert int to boolean
    cell_type <- c.downField("type").as[String] // Yosys uses "type" not "cell_type"
    parameters <- c.downField("parameters").as[Map[String, String]]
    attributes <- c.downField("attributes").as[Map[String, String]]
    port_directions <- c.downField("port_directions").as[Map[String, String]]
    connections <- c.downField("connections").as[Map[String, List[Json]]]
  } yield YosysCell(hide_name, cell_type, parameters, attributes, port_directions, connections)
}

implicit val yosysModuleDecoder: Decoder[YosysModule] = Decoder.instance { c =>
  for {
    attributes <- c.downField("attributes").as[Map[String, String]]
    ports <- c.downField("ports").as[Map[String, YosysPort]]
    cells <- c.downField("cells").as[Map[String, YosysCell]]
    netnames <- c.downField("netnames").as[Map[String, YosysNetname]]
  } yield YosysModule(attributes, ports, cells, netnames)
}

implicit val yosysRootDecoder: Decoder[YosysRoot] = Decoder.instance { c =>
  for {
    creator <- c.downField("creator").as[String]
    modules <- c.downField("modules").as[Map[String, YosysModule]]
  } yield YosysRoot(creator, modules)
}


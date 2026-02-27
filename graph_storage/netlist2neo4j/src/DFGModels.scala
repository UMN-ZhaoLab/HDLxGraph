package netlist2neo4j

case class DFGNodeModel(
  id: String,
  name: String,
  nodeKind: String,
  module: Option[String] = None,
  line: Option[Int] = None,
  operator: Option[String] = None
)

case class DFGEdgeModel(
  src: String,
  dst: String,
  edgeType: String = "data",
  line: Option[Int] = None
)

case class DFGGraph(
  nodes: List[DFGNodeModel],
  edges: List[DFGEdgeModel],
  rootSignals: List[String] = Nil
)


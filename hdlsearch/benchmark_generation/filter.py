# import utils
import sys

from neo4j import GraphDatabase

sys.path.append("../")
from neo4j_configuration import auth, uri

driver = GraphDatabase.driver(uri, auth=auth)


def find_random_nodes(limit=10, signals_per_module=50):
    def _find_random_nodes(tx, limit, signals_per_module):
        result = tx.run(
            """
            MATCH (m:Module)
            WITH m
            ORDER BY RAND()

            MATCH (m)-[:CONTAINS]->(block:Block)
            WITH m, block
            ORDER BY block.id
            WITH m, collect(DISTINCT {
                id: block.id,
                filename: block.filename,
                begin: block.begin,
                end: block.end
            }) AS all_blocks

            UNWIND all_blocks AS block_info
            MATCH (b:Block {id: block_info.id})-[:CONTAINS]->(s:Signal)
            WITH m, all_blocks, block_info, collect(DISTINCT s.id) AS signals_in_block

            WITH m, all_blocks, collect({
                block_id: block_info.id,
                signals: signals_in_block
            }) AS block_signals_list

            RETURN m.id AS module_id,
                   m.filename AS module_filename,
                   all_blocks AS blocks,
                   block_signals_list AS block_signals
            LIMIT $limit
            """,
            limit=limit,
            signals_per_module=signals_per_module,
        )
        return [record.data() for record in result]

    with driver.session() as session:
        result = session.execute_read(_find_random_nodes, limit, signals_per_module)
    return result


if __name__ == "__main__":
    result = find_random_nodes()
    print(result)

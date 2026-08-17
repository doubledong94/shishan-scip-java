package org.scip_code.scip_java.aggregator.graph;

import java.util.Map;

/**
 * Minimal sink for graph nodes/edges. Implemented by {@link Neo4jGraphWriter} for real databases
 * and by in-memory fakes in tests, so {@link GraphExtractor} stays independent of Neo4j.
 */
public interface GraphSink extends AutoCloseable {

  void deleteProject();

  void ensureSchema();

  void addNode(String label, String id, Map<String, Object> props);

  void addEdge(
      String type, String fromLabel, String fromId, String toLabel, String toId);

  void flush();

  @Override
  void close();
}

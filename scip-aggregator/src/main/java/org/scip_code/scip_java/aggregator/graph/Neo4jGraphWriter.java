package org.scip_code.scip_java.aggregator.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;

/**
 * Batched Neo4j writer used by the aggregator to stream the code graph into the database.
 *
 * <p>Design:
 *
 * <ul>
 *   <li>Nodes are written per label with {@code UNWIND ... MERGE (n:Label {id: r.id}) SET n +=
 *       r.props}, keyed by a stable, globally unique {@code id}.
 *   <li>Edges are written per (type, fromLabel, toLabel) with {@code UNWIND ... MATCH
 *       (a:fromLabel {id}) MATCH (b:toLabel {id}) MERGE (a)-[:TYPE]->(b)}.
 *   <li>Batching (configurable rows per statement) keeps memory bounded for real projects.
 *   <li>Idempotency: {@link #deleteProject} removes all project nodes before a (re)import, so a
 *       re-run never stacks dirty data.
 * </ul>
 */
public final class Neo4jGraphWriter implements GraphSink {

  private static final String PROP_ID = "id";
  private static final String PROP_PROJECT = "projectId";

  private static final String[] LABELS = {
    GraphModel.LABEL_CLASS,
    GraphModel.LABEL_METHOD,
    GraphModel.LABEL_FIELD,
    GraphModel.LABEL_VALUE,
    GraphModel.LABEL_CALLED_METHOD,
    GraphModel.LABEL_CONDITION,
  };

  private final Driver driver;
  private final SessionConfig sessionConfig;
  private final String project;
  private final int batchSize;

  private static final class NodeRow {
    final String label;
    final String id;
    final Map<String, Object> props;

    NodeRow(String label, String id, Map<String, Object> props) {
      this.label = label;
      this.id = id;
      this.props = props;
    }
  }

  private static final class EdgeRow {
    final String type;
    final String fromLabel;
    final String fromId;
    final String toLabel;
    final String toId;

    EdgeRow(String type, String fromLabel, String fromId, String toLabel, String toId) {
      this.type = type;
      this.fromLabel = fromLabel;
      this.fromId = fromId;
      this.toLabel = toLabel;
      this.toId = toId;
    }
  }

  private final List<NodeRow> pendingNodes = new ArrayList<>();
  private final List<EdgeRow> pendingEdges = new ArrayList<>();

  public Neo4jGraphWriter(Neo4jGraphConfig config, String project) {
    this(config, project, 5000);
  }

  public Neo4jGraphWriter(Neo4jGraphConfig config, String project, int batchSize) {
    this.driver = GraphDatabase.driver(config.uri, auth(config));
    this.sessionConfig =
        config.database.isEmpty()
            ? SessionConfig.defaultConfig()
            : SessionConfig.forDatabase(config.database);
    this.project = project;
    this.batchSize = batchSize;
  }

  private static org.neo4j.driver.AuthToken auth(Neo4jGraphConfig config) {
    return org.neo4j.driver.AuthTokens.basic(config.user.isEmpty() ? "neo4j" : config.user, config.password);
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Deletes every node belonging to {@code project} (and its relationships). */
  @Override
  public void deleteProject() {
    run("MATCH (n {" + PROP_PROJECT + ": $project}) DETACH DELETE n", Map.of("project", project));
  }

  /** Creates idempotent unique indexes for the node ids of every label. */
  @Override
  public void ensureSchema() {
    for (String label : LABELS) {
      run(
          "CREATE INDEX IF NOT EXISTS FOR (n:"
              + label
              + ") ON (n."
              + PROP_ID
              + ")",
          Map.of());
    }
  }

  @Override
  public void addNode(String label, String id, Map<String, Object> props) {
    Map<String, Object> full = new LinkedHashMap<>(props);
    full.put(PROP_ID, id);
    full.put(PROP_PROJECT, project);
    pendingNodes.add(new NodeRow(label, id, full));
    if (pendingNodes.size() >= batchSize) flushNodes();
  }

  @Override
  public void addEdge(
      String type, String fromLabel, String fromId, String toLabel, String toId) {
    pendingEdges.add(new EdgeRow(type, fromLabel, fromId, toLabel, toId));
    if (pendingEdges.size() >= batchSize) flushEdges();
  }

  /** Flushes any buffered nodes and edges. Call once before {@link #close()}. */
  @Override
  public void flush() {
    flushNodes();
    flushEdges();
  }

  @Override
  public void close() {
    flush();
    driver.close();
  }

  // ---------------------------------------------------------------------------
  // Batching
  // ---------------------------------------------------------------------------

  private void flushNodes() {
    if (pendingNodes.isEmpty()) return;
    // Group rows by label so each MERGE statement targets a single label.
    Map<String, List<NodeRow>> byLabel = new LinkedHashMap<>();
    for (NodeRow row : pendingNodes) {
      byLabel.computeIfAbsent(row.label, k -> new ArrayList<>()).add(row);
    }
    for (Map.Entry<String, List<NodeRow>> entry : byLabel.entrySet()) {
      String label = entry.getKey();
      List<NodeRow> rows = entry.getValue();
      List<Map<String, Object>> data = new ArrayList<>(rows.size());
      for (NodeRow row : rows) {
        data.add(row.props);
      }
      run(
          "UNWIND $rows AS r MERGE (n:" + label + " {" + PROP_ID + ": r.id}) SET n += r",
          Map.of("rows", data));
    }
    pendingNodes.clear();
  }

  private void flushEdges() {
    if (pendingEdges.isEmpty()) return;
    // Group by (type, fromLabel, toLabel).
    Map<String, List<EdgeRow>> byType = new LinkedHashMap<>();
    for (EdgeRow row : pendingEdges) {
      byType.computeIfAbsent(
              key(row), k -> new ArrayList<>())
          .add(row);
    }
    for (Map.Entry<String, List<EdgeRow>> entry : byType.entrySet()) {
      List<EdgeRow> rows = entry.getValue();
      List<Map<String, Object>> data = new ArrayList<>(rows.size());
      for (EdgeRow row : rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", row.fromId);
        m.put("to", row.toId);
        data.add(m);
      }
      EdgeRow first = rows.get(0);
      String query =
          "UNWIND $rows AS r "
              + "MATCH (a:"
              + first.fromLabel
              + " {"
              + PROP_ID
              + ": r.from}) "
              + "MATCH (b:"
              + first.toLabel
              + " {"
              + PROP_ID
              + ": r.to}) "
              + "MERGE (a)-[:"
              + first.type
              + "]->(b)";
      run(query, Map.of("rows", data));
    }
    pendingEdges.clear();
  }

  private static String key(EdgeRow row) {
    return row.type + "|" + row.fromLabel + "|" + row.toLabel;
  }

  private void run(String query, Map<String, Object> params) {
    try (var session = driver.session(sessionConfig)) {
      session.executeWrite(tx -> tx.run(query, params).consume());
    }
  }
}

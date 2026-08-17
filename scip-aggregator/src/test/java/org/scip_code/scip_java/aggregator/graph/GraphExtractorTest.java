package org.scip_code.scip_java.aggregator.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.ScipRange;
import org.scip_code.scip_java.shared.SyntaxTree;

class GraphExtractorTest {

  /** In-memory {@link GraphSink} that records everything it is given. */
  private static final class MemorySink implements GraphSink {
    final List<Map<String, Object>> nodes = new ArrayList<>();
    final List<Map<String, Object>> edges = new ArrayList<>();

    @Override
    public void deleteProject() {}

    @Override
    public void ensureSchema() {}

    @Override
    public void addNode(String label, String id, Map<String, Object> props) {
      Map<String, Object> row = new LinkedHashMap<>(props);
      row.put("_label", label);
      row.put("_id", id);
      nodes.add(row);
    }

    @Override
    public void addEdge(String type, String fromLabel, String fromId, String toLabel, String toId) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("_type", type);
      row.put("_from", fromId);
      row.put("_to", toId);
      edges.add(row);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private static SyntaxTree.OccurrenceData def(String symbol, String syntaxKind, int line) {
    SyntaxTree.OccurrenceData occ = new SyntaxTree.OccurrenceData();
    occ.symbol = symbol;
    occ.role = 1;
    occ.syntaxKind = syntaxKind;
    occ.range = new ScipRange(line, 0, line, 1);
    return occ;
  }

  private static SyntaxTree.OccurrenceData ref(String symbol, String syntaxKind, int line) {
    SyntaxTree.OccurrenceData occ = new SyntaxTree.OccurrenceData();
    occ.symbol = symbol;
    occ.role = 0;
    occ.syntaxKind = syntaxKind;
    occ.range = new ScipRange(line, 0, line, 1);
    return occ;
  }

  private static SyntaxTree.Node node(String kind, int line, SyntaxTree.OccurrenceData... occs) {
    SyntaxTree.Node n = new SyntaxTree.Node(kind);
    n.range = new ScipRange(line, 0, line, 10);
    for (SyntaxTree.OccurrenceData occ : occs) n.occurrences.add(occ);
    return n;
  }

  private static SymbolInformation info(SymbolInformation.Kind kind, String displayName) {
    return SymbolInformation.newBuilder()
        .setSymbol("unused")
        .setDisplayName(displayName)
        .setKind(kind)
        .build();
  }

  @Test
  void extractsDeclarationCallAndBranchLayers() {
    // class A { int f; void a() {} void m() { int x = 0; if (x>0) a(); } }
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls =
        node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    cls.children.add(node("VARIABLE", 2, def("pkg/A#f.", "IdentifierConstant", 2)));
    SyntaxTree.Node methodA =
        node("METHOD", 3, def("pkg/A#a().", "IdentifierFunctionDefinition", 3));
    SyntaxTree.Node methodM =
        node("METHOD", 4, def("pkg/A#m().", "IdentifierFunctionDefinition", 4));
    methodM.children.add(node("VARIABLE", 5, def("local 0", "IdentifierLocal", 5)));
    SyntaxTree.Node ifNode = node("IF", 6);
    ifNode.children.add(node("METHOD_INVOCATION", 7, ref("pkg/A#a().", "IdentifierFunction", 7)));
    methodM.children.add(ifNode);
    cls.children.add(methodA);
    cls.children.add(methodM);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#f.", info(SymbolInformation.Kind.Field, "f"));
    symbols.put("pkg/A#a().", info(SymbolInformation.Kind.Method, "a"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("local 0", info(SymbolInformation.Kind.Variable, "x"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);

    assertTrue(hasNode(sink, GraphModel.LABEL_CLASS, "test::pkg/A#"), "class node");
    assertTrue(
        hasNode(sink, GraphModel.LABEL_METHOD, "test::pkg/A#m()."), "method m node");
    assertTrue(
        hasNode(sink, GraphModel.LABEL_METHOD, "test::pkg/A#a()."), "method a node");
    assertTrue(
        hasNode(sink, GraphModel.LABEL_FIELD, "test::pkg/A#f."), "field node");
    assertTrue(
        hasNode(sink, GraphModel.LABEL_VALUE, "test::Foo.java::local 0"), "local var node");
    assertTrue(hasEdge(sink, GraphModel.REL_DECLARES, "test::pkg/A#", "test::pkg/A#m()."));
    assertTrue(hasEdge(sink, GraphModel.REL_DECLARES, "test::pkg/A#", "test::pkg/A#a()."));
    assertTrue(hasEdge(sink, GraphModel.REL_DECLARES, "test::pkg/A#", "test::pkg/A#f."));

    // Call layer: exactly one CalledMethod, CALLS to a(), SCOPED_BY the IF branch.
    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    assertEquals(1, calls.size(), "one call site");
    String callId = (String) calls.get(0).get("_id");
    assertTrue(hasEdge(sink, GraphModel.REL_CALLS, callId, "test::pkg/A#a()."));
    assertTrue(hasEdge(sink, GraphModel.REL_SCOPED_BY, callId, null), "call scoped to branch");

    // Branch layer: each method gets a METHOD-kind root condition; the IF is a branch of m's root.
    List<Map<String, Object>> conditions = nodesOf(sink, GraphModel.LABEL_CONDITION);
    assertEquals(3, conditions.size(), "two method roots + one if condition");
    String ifCond =
        conditions.stream()
            .filter(c -> GraphModel.CONDITION_KIND_IF.equals(c.get("kind")))
            .map(c -> (String) c.get("_id"))
            .findFirst()
            .orElseThrow();
    String mRoot =
        conditions.stream()
            .filter(c -> GraphModel.CONDITION_KIND_METHOD.equals(c.get("kind")))
            .map(c -> (String) c.get("_id"))
            .filter(id -> hasEdge(sink, GraphModel.REL_ROOT, "test::pkg/A#m().", id))
            .findFirst()
            .orElseThrow();
    assertTrue(hasEdge(sink, GraphModel.REL_SUB, mRoot, ifCond), "if is sub of m's root");
    assertTrue(hasEdge(sink, GraphModel.REL_LEADS_TO, ifCond, callId));
  }

  private static boolean hasNode(MemorySink sink, String label, String id) {
    for (Map<String, Object> row : sink.nodes) {
      if (label.equals(row.get("_label")) && id.equals(row.get("_id"))) return true;
    }
    return false;
  }

  private static List<Map<String, Object>> nodesOf(MemorySink sink, String label) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> row : sink.nodes) {
      if (label.equals(row.get("_label"))) out.add(row);
    }
    return out;
  }

  private static boolean hasEdge(MemorySink sink, String type, String from, String to) {
    for (Map<String, Object> row : sink.edges) {
      if (type.equals(row.get("_type"))
          && (from == null || from.equals(row.get("_from")))
          && (to == null || to.equals(row.get("_to")))) {
        return true;
      }
    }
    return false;
  }
}

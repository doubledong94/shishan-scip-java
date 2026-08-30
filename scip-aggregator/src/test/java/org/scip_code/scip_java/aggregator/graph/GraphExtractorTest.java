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
      addEdge(type, fromLabel, fromId, toLabel, toId, null);
    }

    @Override
    public void addEdge(
        String type, String fromLabel, String fromId, String toLabel, String toId, Map<String, Object> props) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("_type", type);
      row.put("_from", fromId);
      row.put("_to", toId);
      if (props != null) row.put("_props", props);
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
    extractor.emitRelationships();

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

    // Call layer: exactly one CalledMethod, CALLS to a(), anchored to the IF branch via LEADS_TO
    // (Condition->CalledMethod).
    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    assertEquals(1, calls.size(), "one call site");
    String callId = (String) calls.get(0).get("_id");
    assertTrue(hasEdge(sink, GraphModel.REL_CALLS, callId, "test::pkg/A#a()."));
    assertTrue(hasEdge(sink, GraphModel.REL_LEADS_TO, null, callId), "call anchored to branch via LEADS_TO");

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

  @Test
  void extractsKotlinStyleTree() {
    // Kotlin: structural nodes (CLASS/FUN) carry no occurrence; the definition sits on the name
    // IDENTIFIER descendant. Call site is CALL_EXPRESSION; args live in VALUE_ARGUMENT_LIST.
    SyntaxTree.Node cls = node("CLASS", 0);
    cls.children.add(node("IDENTIFIER", 1, def("pkg/Foo#", "IdentifierType", 1)));
    SyntaxTree.Node fun = node("FUN", 2);
    fun.children.add(node("IDENTIFIER", 3, def("pkg/Foo#bar().", "IdentifierFunctionDefinition", 3)));
    fun.children.add(node("IDENTIFIER", 4, def("pkg/Foo#bar().(x)", "IdentifierParameter", 4)));
    SyntaxTree.Node ifNode = node("IF", 5);
    SyntaxTree.Node call = node("CALL_EXPRESSION", 6);
    call.children.add(node("OPERATION_REFERENCE", 7, ref("pkg/Foo#baz().", "IdentifierFunction", 7)));
    SyntaxTree.Node argList = node("VALUE_ARGUMENT_LIST", 8);
    SyntaxTree.Node valueArg = node("VALUE_ARGUMENT", 9);
    valueArg.children.add(node("IDENTIFIER", 10, ref("local 1", "Identifier", 10)));
    argList.children.add(valueArg);
    call.children.add(argList);
    ifNode.children.add(call);
    fun.children.add(ifNode);
    cls.children.add(fun);
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/Foo#", info(SymbolInformation.Kind.Class, "Foo"));
    symbols.put("pkg/Foo#bar().", info(SymbolInformation.Kind.Method, "bar"));
    symbols.put("pkg/Foo#baz().", info(SymbolInformation.Kind.Method, "baz"));
    symbols.put("pkg/Foo#bar().(x)", info(SymbolInformation.Kind.Parameter, "x"));
    symbols.put("local 1", info(SymbolInformation.Kind.Variable, "y"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    assertTrue(hasNode(sink, GraphModel.LABEL_CLASS, "kotest::pkg/Foo#"), "kotlin class");
    assertTrue(hasNode(sink, GraphModel.LABEL_METHOD, "kotest::pkg/Foo#bar()."), "kotlin method");
    assertTrue(
        hasEdge(sink, GraphModel.REL_DECLARES, "kotest::pkg/Foo#", "kotest::pkg/Foo#bar()."),
        "kotlin class declares method");
    assertTrue(
        hasNode(sink, GraphModel.LABEL_VALUE, "kotest::pkg/Foo#bar().(x)"), "kotlin param");
    assertTrue(
        hasEdge(sink, GraphModel.REL_HAS_PARAM, "kotest::pkg/Foo#bar().", "kotest::pkg/Foo#bar().(x)"),
        "kotlin method has param");

    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    assertEquals(1, calls.size(), "one kotlin call site");
    String callId = (String) calls.get(0).get("_id");
    assertTrue(hasEdge(sink, GraphModel.REL_CALLS, callId, "kotest::pkg/Foo#baz()."));
    List<Map<String, Object>> args = nodesOf(sink, GraphModel.LABEL_VALUE);
    boolean argLinked =
        args.stream()
            .filter(v -> GraphModel.VALUE_KIND_CALLED_PARAM.equals(v.get("kind")))
            .anyMatch(v -> hasEdge(sink, GraphModel.REL_ARG_OF, (String) v.get("_id"), callId));
    assertTrue(argLinked, "kotlin argument ARG_OF the call");
  }

  @Test
  void extractsRuntimeEdges() {
    // class A { int f; void m() { f = g; if (f>0) a.b(); else if (x>1) c(); } }
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    cls.children.add(node("VARIABLE", 2, def("pkg/A#f.", "IdentifierConstant", 2)));

    SyntaxTree.Node method =
        node("METHOD", 3, def("pkg/A#m().", "IdentifierFunctionDefinition", 3));

    // f = g
    SyntaxTree.Node assign = node("ASSIGNMENT", 4);
    assign.children.add(node("IDENTIFIER", 5, ref("pkg/A#f.", "IdentifierConstant", 5)));
    assign.children.add(node("IDENTIFIER", 6, ref("pkg/G#g.", "IdentifierConstant", 6)));
    method.children.add(assign);

    // if (f > 0) a.b(); else if (x > 1) c();
    SyntaxTree.Node ifNode = node("IF", 7);
    SyntaxTree.Node condExpr = node("BINARY_EXPRESSION", 8);
    condExpr.children.add(node("IDENTIFIER", 9, ref("pkg/A#f.", "IdentifierConstant", 9)));
    ifNode.children.add(condExpr);
    SyntaxTree.Node thenBlock = node("BLOCK", 10);
    SyntaxTree.Node call = node("CALL_EXPRESSION", 11);
    SyntaxTree.Node dot = node("DOT_QUALIFIED_EXPRESSION", 12);
    dot.children.add(node("REFERENCE_EXPRESSION", 13, ref("pkg/B#a.", "IdentifierConstant", 13)));
    dot.children.add(node("IDENTIFIER", 14, ref("pkg/B#b().", "IdentifierFunction", 14)));
    call.children.add(dot);
    thenBlock.children.add(call);
    ifNode.children.add(thenBlock);
    SyntaxTree.Node elseIf = node("IF", 15);
    elseIf.children.add(node("IDENTIFIER", 16, ref("pkg/C#x.", "IdentifierConstant", 16)));
    ifNode.children.add(elseIf);
    method.children.add(ifNode);

    cls.children.add(method);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#f.", info(SymbolInformation.Kind.Field, "f"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/G#g.", info(SymbolInformation.Kind.Field, "g"));
    symbols.put("pkg/B#a.", info(SymbolInformation.Kind.Field, "a"));
    symbols.put("pkg/B#b().", info(SymbolInformation.Kind.Method, "b"));
    symbols.put("pkg/C#c().", info(SymbolInformation.Kind.Method, "c"));
    symbols.put("pkg/C#x.", info(SymbolInformation.Kind.Field, "x"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    // Runtime nodes are distinct from the declaration node.
    assertTrue(hasNode(sink, GraphModel.LABEL_FIELD, "test::pkg/A#f."), "field declaration");
    List<Map<String, Object>> fieldRuntimes =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> GraphModel.VALUE_KIND_FIELD.equals(v.get("kind")))
            .toList();
    assertEquals(
        5,
        fieldRuntimes.size(),
        "five runtime field values: f-write, g-read, f-read, a-read(receiver), x-read(else cond)");
    assertTrue(
        fieldRuntimes.stream().noneMatch(v -> "test::pkg/A#f.".equals(v.get("_id"))),
        "runtime values must not collide with the declaration id");

    // FLOWS from assignment (g -> f) and last-write (f write -> f read in condition).
    boolean assignFlow =
        edgesOf(sink, GraphModel.REL_FLOWS).stream()
            .anyMatch(e -> ((String) e.get("_to")).contains("Foo.java#5:0:FIELD"));
    assertTrue(assignFlow, "assignment g flows into f write (line 5)");
    boolean lastWriteFlow =
        edgesOf(sink, GraphModel.REL_FLOWS).stream()
            .anyMatch(
                e ->
                    ((String) e.get("_from")).contains("Foo.java#5:0:FIELD")
                        && ((String) e.get("_to")).contains("Foo.java#9:0:FIELD"));
    assertTrue(lastWriteFlow, "f write (line 5) flows into f read (line 9)");

    // CONTROLS: f value guards the if condition.
    String ifCond =
        conditionsOfKind(sink, GraphModel.CONDITION_KIND_IF).stream()
            .filter(c -> ((String) c.get("_id")).contains("Foo.java#7"))
            .findFirst()
            .map(c -> (String) c.get("_id"))
            .orElseThrow();
    boolean controls =
        edgesOf(sink, GraphModel.REL_CONTROLS).stream()
            .anyMatch(
                e ->
                    ((String) e.get("_from")).contains("Foo.java#9:0:FIELD")
                        && ifCond.equals(e.get("_to")));
    assertTrue(controls, "f value controls the if branch");

    // REF: receiver a reaches the called method b.
    boolean ref =
        edgesOf(sink, GraphModel.REL_REF).stream()
            .anyMatch(e -> ((String) e.get("_from")).contains("Foo.java#13:0:FIELD"));
    assertTrue(ref, "receiver a REF the call");

    // ELSE: else-if chain.
    String elseIfCond =
        conditionsOfKind(sink, GraphModel.CONDITION_KIND_IF).stream()
            .filter(c -> ((String) c.get("_id")).contains("Foo.java#15"))
            .findFirst()
            .map(c -> (String) c.get("_id"))
            .orElseThrow();
    assertTrue(hasEdge(sink, GraphModel.REL_ELSE, ifCond, elseIfCond), "else-if chain");
  }

  @Test
  void dataFlowRespectsBranches() {
    // void m() { if (c) { f = 1; } else { g = f; } h = f; }
    // Branch A writes f; branch B reads f (must NOT see A's write);
    // the read after the if MUST see A's write (union merge).
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node method =
        node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));

    SyntaxTree.Node ifNode = node("IF", 3);
    SyntaxTree.Node condExpr = node("IDENTIFIER", 4, ref("pkg/A#c.", "IdentifierConstant", 4));
    ifNode.children.add(condExpr);

    // then: f = 1  (branch A)
    SyntaxTree.Node thenBlock = node("BLOCK", 5);
    SyntaxTree.Node assignA = node("ASSIGNMENT", 6);
    assignA.children.add(node("IDENTIFIER", 7, ref("pkg/A#f.", "IdentifierConstant", 7)));
    assignA.children.add(node("INT_LITERAL", 6)); // literal RHS (no value reference)
    thenBlock.children.add(assignA);
    ifNode.children.add(thenBlock);

    // else: g = f  (branch B)
    SyntaxTree.Node elseBlock = node("BLOCK", 8);
    SyntaxTree.Node assignB = node("ASSIGNMENT", 9);
    assignB.children.add(node("IDENTIFIER", 10, ref("pkg/A#g.", "IdentifierConstant", 10)));
    assignB.children.add(node("IDENTIFIER", 11, ref("pkg/A#f.", "IdentifierConstant", 11)));
    elseBlock.children.add(assignB);
    ifNode.children.add(elseBlock);
    method.children.add(ifNode);

    // after: h = f
    SyntaxTree.Node assignAfter = node("ASSIGNMENT", 12);
    assignAfter.children.add(node("IDENTIFIER", 13, ref("pkg/A#h.", "IdentifierConstant", 13)));
    assignAfter.children.add(node("IDENTIFIER", 14, ref("pkg/A#f.", "IdentifierConstant", 14)));
    method.children.add(assignAfter);

    cls.children.add(method);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "f.", "g.", "h."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> flows = edgesOf(sink, GraphModel.REL_FLOWS);
    // Branch A f-write id = line 7; branch B f-read id = line 11; after f-read id = line 14.
    String aWrite = "test::Foo.java#7:0:FIELD";
    String bRead = "test::Foo.java#11:0:FIELD";
    String afterRead = "test::Foo.java#14:0:FIELD";

    boolean crossBranch =
        flows.stream()
            .anyMatch(
                e ->
                    aWrite.equals(e.get("_from"))
                        && bRead.equals(e.get("_to")));
    assertTrue(!crossBranch, "branch A write must NOT flow into branch B read");
    boolean afterSeesA =
        flows.stream()
            .anyMatch(
                e ->
                    aWrite.equals(e.get("_from"))
                        && afterRead.equals(e.get("_to")));
    assertTrue(afterSeesA, "read after the if sees branch A's write (union merge)");
    // The else read is an unwritten read (no source) → no FLOWS into it.
    boolean bReadHasSource =
        flows.stream().anyMatch(e -> bRead.equals(e.get("_to")));
    assertTrue(!bReadHasSource, "branch B f-read has no FLOWS source (no preceding write in scope)");
  }

  @Test
  void definiteAssignmentAndReturnBranch() {
    // Build two methods in one class.
    //   void a() { f = 0; if (c) { f = 1; } else { f = 2; } h = f; }   // definite assignment
    //   void b() { f = 0; if (c) { f = 1; return x; } else { f = 2; } h = f; }  // return branch
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));

    // ---- method a ----
    SyntaxTree.Node a = node("METHOD", 2, def("pkg/A#a().", "IdentifierFunctionDefinition", 2));
    a.children.add(assign("pkg/A#f.", 10, "pkg/A#f0.", 9));
    SyntaxTree.Node ifA = node("IF", 11);
    ifA.children.add(node("IDENTIFIER", 12, ref("pkg/A#c.", "IdentifierConstant", 12)));
    SyntaxTree.Node thenA = node("BLOCK", 13);
    thenA.children.add(assign("pkg/A#f.", 14, "pkg/A#lit1.", 13));
    ifA.children.add(thenA);
    SyntaxTree.Node elseA = node("BLOCK", 16);
    elseA.children.add(assign("pkg/A#f.", 17, "pkg/A#lit2.", 16));
    ifA.children.add(elseA);
    a.children.add(ifA);
    a.children.add(assign("pkg/A#h.", 19, "pkg/A#f.", 18));
    cls.children.add(a);

    // ---- method b ----
    SyntaxTree.Node b = node("METHOD", 20, def("pkg/A#b().", "IdentifierFunctionDefinition", 20));
    b.children.add(assign("pkg/A#f.", 22, "pkg/A#f0.", 21));
    SyntaxTree.Node ifB = node("IF", 23);
    ifB.children.add(node("IDENTIFIER", 24, ref("pkg/A#c.", "IdentifierConstant", 24)));
    SyntaxTree.Node thenB = node("BLOCK", 25);
    thenB.children.add(assign("pkg/A#f.", 26, "pkg/A#lit1.", 25));
    SyntaxTree.Node ret = node("RETURN", 27);
    ret.children.add(node("IDENTIFIER", 28, ref("pkg/A#x.", "IdentifierConstant", 28)));
    thenB.children.add(ret);
    ifB.children.add(thenB);
    SyntaxTree.Node elseB = node("BLOCK", 29);
    elseB.children.add(assign("pkg/A#f.", 30, "pkg/A#lit2.", 29));
    ifB.children.add(elseB);
    b.children.add(ifB);
    b.children.add(assign("pkg/A#h.", 32, "pkg/A#f.", 31));
    cls.children.add(b);

    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#a().", info(SymbolInformation.Kind.Method, "a"));
    symbols.put("pkg/A#b().", info(SymbolInformation.Kind.Method, "b"));
    for (String f : new String[] {"c.", "f.", "h.", "x."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> flows = edgesOf(sink, GraphModel.REL_FLOWS);
    java.util.function.BiPredicate<String, String> flowsTo =
        (fromId, toId) ->
            flows.stream()
                .anyMatch(e -> fromId.equals(e.get("_from")) && toId.equals(e.get("_to")));

    // Method a: definite assignment clears pre-branch f-write (line 10).
    String preA = "test::Foo.java#10:0:FIELD";
    String f1A = "test::Foo.java#14:0:FIELD";
    String f2A = "test::Foo.java#17:0:FIELD";
    String hA = "test::Foo.java#18:0:FIELD";
    assertTrue(!flowsTo.test(preA, hA), "definite assignment: pre-branch write must not reach after");
    assertTrue(flowsTo.test(f1A, hA), "branch write f=1 reaches after (union)");
    assertTrue(flowsTo.test(f2A, hA), "branch write f=2 reaches after (union)");

    // Method b: the then branch returns, so its f-write (line 26) must NOT reach after.
    String f1B = "test::Foo.java#26:0:FIELD";
    String f2B = "test::Foo.java#30:0:FIELD";
    String hB = "test::Foo.java#31:0:FIELD";
    assertTrue(!flowsTo.test(f1B, hB), "returning branch write must not reach after");
    assertTrue(flowsTo.test(f2B, hB), "non-returning branch write reaches after");
  }

  @Test
  void loopCarriedDependencyFeedback() {
    // void m() { x = 0; while (x < 10) { use(x); x = x + 1; } }
    // The read of x at the loop top sees the outer write (first iteration) AND the loop's own
    // write that happens later (next iterations) via loop feedback.
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node method =
        node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));

    method.children.add(assign("pkg/A#x.", 10, "pkg/A#zero.", 9));

    SyntaxTree.Node loop = node("WHILE_LOOP", 11);
    loop.children.add(node("IDENTIFIER", 12, ref("pkg/A#x.", "IdentifierConstant", 12)));
    SyntaxTree.Node body = node("BLOCK", 13);
    body.children.add(node("IDENTIFIER", 14, ref("pkg/A#x.", "IdentifierConstant", 14)));
    body.children.add(assign("pkg/A#x.", 15, "pkg/A#one.", 14));
    loop.children.add(body);
    method.children.add(loop);

    cls.children.add(method);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#x.", info(SymbolInformation.Kind.Field, "x"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> flows = edgesOf(sink, GraphModel.REL_FLOWS);
    java.util.function.BiPredicate<String, String> flowsTo =
        (fromId, toId) ->
            flows.stream()
                .anyMatch(e -> fromId.equals(e.get("_from")) && toId.equals(e.get("_to")));

    String outerWrite = "test::Foo.java#10:0:FIELD";
    String loopWrite = "test::Foo.java#15:0:FIELD";
    String loopRead = "test::Foo.java#14:0:FIELD";
    assertTrue(flowsTo.test(outerWrite, loopRead), "outer write reaches first-iteration read");
    assertTrue(flowsTo.test(loopWrite, loopRead), "loop-carried dependency: loop write feeds back to read");
  }

  @Test
  void fieldWriteThroughReference() {
    // void m(A obj, int x) { obj.f = x; }
    // The write target is the FIELD (through obj); FLOWS x -> field-write; REF obj -> field-write;
    // the base obj is recorded as written.
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node method =
        node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));

    SyntaxTree.Node assign = node("ASSIGNMENT", 3);
    SyntaxTree.Node lhs = node("DOT_QUALIFIED_EXPRESSION", 4);
    lhs.children.add(node("REFERENCE_EXPRESSION", 5, ref("pkg/A#obj.", "IdentifierLocal", 5)));
    lhs.children.add(node("IDENTIFIER", 6, ref("pkg/A#f.", "IdentifierConstant", 6)));
    assign.children.add(lhs);
    assign.children.add(node("REFERENCE_EXPRESSION", 7, ref("pkg/A#x.", "IdentifierLocal", 7)));
    method.children.add(assign);

    cls.children.add(method);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#f.", info(SymbolInformation.Kind.Field, "f"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> flows = edgesOf(sink, GraphModel.REL_FLOWS);
    String xId = "test::Foo.java#7:0:LOCAL_VAR";
    String fWriteId = "test::Foo.java#6:0:FIELD";
    java.util.function.BiPredicate<String, String> flowsTo =
        (from, to) ->
            flows.stream()
                .anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    assertTrue(flowsTo.test(xId, fWriteId), "x flows into the field write (not the base)");

    // REF: the member WRITE is reversed (member -> base) per reversedRef/markUnreadReturn.
    String objId = "test::Foo.java#5:0:LOCAL_VAR";
    boolean ref =
        edgesOf(sink, GraphModel.REL_REF).stream()
            .anyMatch(e -> fWriteId.equals(e.get("_from")) && objId.equals(e.get("_to")));
    assertTrue(ref, "member write REF back to the base obj (reversedRef)");

    // The base obj is recorded as written (reversedRef): a later read of obj sees this access.
    List<Map<String, Object>> objValues =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> GraphModel.VALUE_KIND_LOCAL_VAR.equals(v.get("kind")))
            .filter(v -> "obj".equals(v.get("name")))
            .toList();
    assertTrue(!objValues.isEmpty(), "base obj runtime value exists");
  }

  @Test
  void crossMethodParamAndReturnBinding() {
    // int add(int a, int b) { return a; }  void m() { s = add(x, y); }
    // Param slots of the call bind to the callee's params; the callee's return flows into calledReturn.
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));

    SyntaxTree.Node add = node("METHOD", 2, def("pkg/A#add().", "IdentifierFunctionDefinition", 2));
    add.children.add(node("VARIABLE", 3, def("pkg/A#add().(a)", "IdentifierParameter", 3)));
    add.children.add(node("VARIABLE", 4, def("pkg/A#add().(b)", "IdentifierParameter", 4)));
    SyntaxTree.Node ret = node("RETURN", 5);
    ret.children.add(node("IDENTIFIER", 6, ref("pkg/A#add().(a)", "IdentifierParameter", 6)));
    add.children.add(ret);
    cls.children.add(add);

    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node call = node("CALL_EXPRESSION", 11);
    call.children.add(node("OPERATION_REFERENCE", 12, ref("pkg/A#add().", "IdentifierFunction", 12)));
    SyntaxTree.Node argList = node("VALUE_ARGUMENT_LIST", 13);
    SyntaxTree.Node va1 = node("VALUE_ARGUMENT", 14);
    va1.children.add(node("REFERENCE_EXPRESSION", 15, ref("pkg/A#x.", "IdentifierConstant", 15)));
    argList.children.add(va1);
    SyntaxTree.Node va2 = node("VALUE_ARGUMENT", 16);
    va2.children.add(node("REFERENCE_EXPRESSION", 17, ref("pkg/A#y.", "IdentifierConstant", 17)));
    argList.children.add(va2);
    call.children.add(argList);
    m.children.add(call);
    cls.children.add(m);

    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#add().", info(SymbolInformation.Kind.Method, "add"));
    symbols.put("pkg/A#add().(a)", info(SymbolInformation.Kind.Parameter, "a"));
    symbols.put("pkg/A#add().(b)", info(SymbolInformation.Kind.Parameter, "b"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#x.", info(SymbolInformation.Kind.Field, "x"));
    symbols.put("pkg/A#y.", info(SymbolInformation.Kind.Field, "y"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> flows = edgesOf(sink, GraphModel.REL_FLOWS);
    java.util.function.BiPredicate<String, String> flowsTo =
        (from, to) ->
            flows.stream()
                .anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    // Param binding: the two calledParam slots flow into the callee's parameter declarations.
    String paramA = "test::pkg/A#add().(a)";
    String paramB = "test::pkg/A#add().(b)";
    boolean paramAHasSource =
        flows.stream().anyMatch(e -> paramA.equals(e.get("_to")));
    boolean paramBHasSource =
        flows.stream().anyMatch(e -> paramB.equals(e.get("_to")));
    assertTrue(paramAHasSource, "calledParam slot 0 binds to callee param a");
    assertTrue(paramBHasSource, "calledParam slot 1 binds to callee param b");

    // Return binding: the callee's return slot flows into the caller's calledReturn.
    List<Map<String, Object>> calledReturns =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> GraphModel.VALUE_KIND_CALLED_RETURN.equals(v.get("kind")))
            .toList();
    assertEquals(1, calledReturns.size(), "one calledReturn for the call");
    String callReturnId = (String) calledReturns.get(0).get("_id");
    List<Map<String, Object>> returnSlots =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> GraphModel.VALUE_KIND_RETURN.equals(v.get("kind")))
            .toList();
    assertEquals(1, returnSlots.size(), "one return slot in the callee");
    String returnSlotId = (String) returnSlots.get(0).get("_id");
    assertTrue(
        flowsTo.test(returnSlotId, callReturnId),
        "callee return slot flows into the caller's calledReturn");
  }

  @Test
  void orderChainContinuesAcrossBlocksAndFunctions() {
    // void m() { e0; if (flag) { a; } else { b; } c; foo(x); y; }
    // void foo(int p) { q; }   -- void method, fall-through exit
    // Order chain: e0→flagCond→{a|b}→c→CalledMethod(foo)→q→calledReturn→y
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));

    SyntaxTree.Node foo = node("METHOD", 2, def("pkg/A#foo().", "IdentifierFunctionDefinition", 2));
    foo.children.add(node("VARIABLE", 3, def("pkg/A#foo().(p)", "IdentifierParameter", 3)));
    SyntaxTree.Node fooBody = node("BLOCK", 4);
    fooBody.children.add(node("IDENTIFIER", 5, ref("pkg/A#q.", "IdentifierConstant", 5)));
    foo.children.add(fooBody);
    cls.children.add(foo);

    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node ifNode = node("IF", 13);
    ifNode.children.add(node("IDENTIFIER", 14, ref("pkg/A#flag.", "IdentifierConstant", 14)));
    SyntaxTree.Node thenB = node("BLOCK", 15);
    thenB.children.add(node("IDENTIFIER", 16, ref("pkg/A#a.", "IdentifierConstant", 16)));
    ifNode.children.add(thenB);
    SyntaxTree.Node elseB = node("BLOCK", 17);
    elseB.children.add(node("IDENTIFIER", 18, ref("pkg/A#b.", "IdentifierConstant", 18)));
    ifNode.children.add(elseB);
    mBody.children.add(ifNode);

    mBody.children.add(node("IDENTIFIER", 19, ref("pkg/A#c.", "IdentifierConstant", 19)));

    SyntaxTree.Node call = node("CALL_EXPRESSION", 20);
    call.children.add(node("OPERATION_REFERENCE", 21, ref("pkg/A#foo().", "IdentifierFunction", 21)));
    SyntaxTree.Node argList = node("VALUE_ARGUMENT_LIST", 22);
    SyntaxTree.Node va = node("VALUE_ARGUMENT", 23);
    va.children.add(node("REFERENCE_EXPRESSION", 24, ref("pkg/A#x.", "IdentifierConstant", 24)));
    argList.children.add(va);
    call.children.add(argList);
    mBody.children.add(call);

    mBody.children.add(node("IDENTIFIER", 25, ref("pkg/A#y.", "IdentifierConstant", 25)));

    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#foo().", info(SymbolInformation.Kind.Method, "foo"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"q.", "e0.", "flag.", "a.", "b.", "c.", "x.", "y."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    List<Map<String, Object>> elseEdges = edgesOf(sink, GraphModel.REL_ELSE);
    java.util.function.BiPredicate<String, String> next = (from, to) ->
        nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String e0 = "test::Foo.java#12:0:FIELD";
    String flagCond = "test::Foo.java#13:0";
    String flag = "test::Foo.java#14:0:FIELD"; // 守卫表达式对 flag 的读取
    String a = "test::Foo.java#16:0:FIELD";
    String b = "test::Foo.java#18:0:FIELD";
    String c = "test::Foo.java#19:0:FIELD";
    String x = "test::Foo.java#24:0:FIELD";

    // Cross-block: the condition is the fork — then branch via NEXT, else branch via ELSE; both
    // branch ends link to the continuation, never skipping via the condition.
    assertTrue(next.test(e0, flag), "e0 before guard read");
    assertTrue(next.test(flag, flagCond), "guard read before condition");
    boolean thenNEXT =
        nexts.stream().anyMatch(e -> flagCond.equals(e.get("_from")) && a.equals(e.get("_to")));
    // else 分支首节点 b 的前置是一个 kind=ELSE 的 Condition 节点(elseB@17)，其从 flagCond 经 ELSE 进入。
    String elseCond = "test::Foo.java#17:0:ELSE";
    boolean elseChain =
        elseEdges.stream().anyMatch(e -> flagCond.equals(e.get("_from")) && elseCond.equals(e.get("_to")));
    boolean elseEntry =
        elseEdges.stream().anyMatch(e -> elseCond.equals(e.get("_from")) && b.equals(e.get("_to")));
    assertTrue(thenNEXT, "then branch entered via NEXT from the condition");
    assertTrue(elseChain, "condition -> else node via ELSE");
    assertTrue(elseEntry, "else node -> else branch first via ELSE");
    assertTrue(next.test(a, c), "then branch end continues after the if");
    assertTrue(next.test(b, c), "else branch end continues after the if");
    assertTrue(!next.test(flagCond, c), "condition must not skip straight to continuation");

    // Branch entry structure: then via NEXT (condition true), else 经 ELSE 进入 kind=ELSE 的条件节点。
    assertTrue(thenNEXT, "then edge entered via NEXT (condition true)");
    assertTrue(elseChain, "else entered via ELSE from condition");
    assertTrue(elseEntry, "else branch first node's predecessor is the ELSE node");

    // Cross-function: call enters callee's first event; void method's exit flows to calledReturn.
    String calledMethod = "test::Foo.java#20:0";
    String q = "test::Foo.java#5:0:FIELD";
    String calledReturn = "test::Foo.java#20:0:CALLED_RETURN";
    String y = "test::Foo.java#25:0:FIELD";
    assertTrue(next.test(calledMethod, q), "call enters callee first event");
    assertTrue(next.test(q, calledReturn), "callee fall-through exit flows to calledReturn");
    assertTrue(next.test(calledReturn, y), "caller continues after the call");
  }

  @Test
  void voidReturnCreatesReturnSlot() {
    // void m() { return; }  → a RETURN slot node exists (order chain has an explicit exit event)
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node body = node("BLOCK", 3);
    SyntaxTree.Node ret = node("RETURN", 4);
    body.children.add(ret);
    m.children.add(body);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    boolean slot =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .anyMatch(v -> GraphModel.VALUE_KIND_RETURN.equals(v.get("kind"))
                && "test::Foo.java#4:0:RETURN".equals(v.get("_id")));
    assertTrue(slot, "void return creates a RETURN slot node");
  }

  private static SyntaxTree.Node assign(String lhsSym, int lhsLine, String rhsSym, int rhsLine) {
    SyntaxTree.Node a = node("ASSIGNMENT", lhsLine);
    a.children.add(node("IDENTIFIER", lhsLine, ref(lhsSym, "IdentifierConstant", lhsLine)));
    a.children.add(node("INT_LITERAL", rhsLine, ref(rhsSym, "IdentifierConstant", rhsLine)));
    return a;
  }

  private static List<Map<String, Object>> conditionsOfKind(MemorySink sink, String kind) {
    return nodesOf(sink, GraphModel.LABEL_CONDITION).stream()
        .filter(c -> kind.equals(c.get("kind")))
        .toList();
  }

  private static List<Map<String, Object>> edgesOf(MemorySink sink, String type) {
    return sink.edges.stream().filter(e -> type.equals(e.get("_type"))).toList();
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

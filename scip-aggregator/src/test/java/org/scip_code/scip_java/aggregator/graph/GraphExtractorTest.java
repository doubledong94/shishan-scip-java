package org.scip_code.scip_java.aggregator.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.NodeKind;
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

    // Call layer: exactly one CalledMethod, CALLS to a().
    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    assertEquals(1, calls.size(), "one call site");
    String callId = (String) calls.get(0).get("_id");
    assertTrue(hasEdge(sink, GraphModel.REL_CALLS, callId, "test::pkg/A#a()."));

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
    // 顺序链：m 根条件 → if 条件的守卫读 → if 条件(条件也入链)。SUB 已移除。
    assertTrue(hasEdge(sink, GraphModel.REL_ROOT, "test::pkg/A#m().", mRoot), "m root condition");
    assertTrue(ifCond != null && !ifCond.isEmpty(), "if condition exists");
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
  void nodeKindCanonicalUnifiesJavaAndKotlin() {
    // 统一节点词表：Kotlin 原生 kind 映射到规范名；规范名 / 未知名（Kotlin 独有构造）透传。
    assertEquals(NodeKind.METHOD, NodeKind.canonical("FUN"));
    assertEquals(NodeKind.METHOD, NodeKind.canonical("PRIMARY_CONSTRUCTOR"));
    assertEquals(NodeKind.METHOD, NodeKind.canonical("SECONDARY_CONSTRUCTOR"));
    assertEquals(NodeKind.WHILE_LOOP, NodeKind.canonical("WHILE"));
    assertEquals(NodeKind.FOR_LOOP, NodeKind.canonical("FOR"));
    assertEquals(NodeKind.DO_WHILE_LOOP, NodeKind.canonical("DO_WHILE"));
    assertEquals(NodeKind.METHOD_INVOCATION, NodeKind.canonical("CALL_EXPRESSION"));
    assertEquals(NodeKind.NEW_CLASS, NodeKind.canonical("CONSTRUCTOR_CALL"));
    assertEquals(NodeKind.MEMBER_SELECT, NodeKind.canonical("DOT_QUALIFIED_EXPRESSION"));
    assertEquals(NodeKind.MEMBER_SELECT, NodeKind.canonical("SAFE_ACCESS_EXPRESSION"));
    assertEquals(NodeKind.ARRAY_ACCESS, NodeKind.canonical("ARRAY_ACCESS_EXPRESSION"));
    assertEquals(NodeKind.INT_LITERAL, NodeKind.canonical("INTEGER_CONSTANT"));
    assertEquals(NodeKind.FLOAT_LITERAL, NodeKind.canonical("REAL_CONSTANT"));
    assertEquals(NodeKind.BOOLEAN_LITERAL, NodeKind.canonical("BOOLEAN_CONSTANT"));
    assertEquals(NodeKind.CHAR_LITERAL, NodeKind.canonical("CHARACTER_CONSTANT"));
    assertEquals(NodeKind.STRING_LITERAL, NodeKind.canonical("STRING_TEMPLATE"));
    assertEquals(NodeKind.NULL_LITERAL, NodeKind.canonical("NULL"));
    // 规范名 / Kotlin 独有构造透传
    assertEquals(NodeKind.METHOD, NodeKind.canonical("METHOD"));
    assertEquals("WHEN", NodeKind.canonical("WHEN"));
    assertEquals("OBJECT_LITERAL", NodeKind.canonical("OBJECT_LITERAL"));
  }

  @Test
  void calledParamSlotNamingIsConsistent() {
    // 外部重载方法符号以 `(+N).` 结尾，同样要能合成 `.(形参名)` —— 之前只认 `().` 导致 symbol 落空。
    assertEquals(
        "scip-java maven maven/test/Foo#bar(+13).(expected)",
        GraphExtractor.paramSymbolFor("scip-java maven maven/test/Foo#bar(+13).", "expected"));
    assertEquals(
        "scip-java maven maven/test/Request#<init>(+1).(url)",
        GraphExtractor.paramSymbolFor("scip-java maven maven/test/Request#<init>(+1).", "url"));
    assertEquals(null, GraphExtractor.paramSymbolFor("…/foo", "x"));

    // 注解文本污染的形参名应回退 #N（保持一致，不吐注解垃圾）；正常名/空白兜底名原样保留。
    assertEquals(
        "#0",
        GraphExtractor.cleanParamName(
            ") @InlineOnly() @JvmName(...) public final inline fun <T> T.apply(block", 0));
    assertEquals("expected", GraphExtractor.cleanParamName("expected", 1));
    assertEquals("HttpUrl arg0", GraphExtractor.cleanParamName("HttpUrl arg0", 1));
    assertEquals("#2", GraphExtractor.cleanParamName(null, 2));
    assertEquals("#3", GraphExtractor.cleanParamName(") @InlineOnly() public final inline fun <reified T", 3));
  }

  @Test
  void localCalleeCallNodeNamedBySourceName() {
    // 匿名对象/lambda/局部函数的被调方只有 `local N` 符号；CALLED_METHOD 的标签应为该局部的源码名
    // （经 localNamesByFile 后置补正），而不是裸数字 `N`。
    SyntaxTree.Node cls = node("CLASS", 0);
    cls.children.add(node("IDENTIFIER", 1, def("pkg/Foo#", "IdentifierType", 1)));
    SyntaxTree.Node fun = node("FUN", 2);
    fun.children.add(node("IDENTIFIER", 3, def("pkg/Foo#bar().", "IdentifierFunctionDefinition", 3)));
    // 局部声明：local 1 的源码名是 `localFn`。
    fun.children.add(node("IDENTIFIER", 4, def("local 1", "IdentifierLocal", 4)));
    // 调用该局部函数。
    SyntaxTree.Node call = node("CALL_EXPRESSION", 5);
    call.children.add(node("OPERATION_REFERENCE", 6, ref("local 1", "IdentifierFunction", 6)));
    call.children.add(node("VALUE_ARGUMENT_LIST", 7));
    fun.children.add(call);
    cls.children.add(fun);
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/Foo#", info(SymbolInformation.Kind.Class, "Foo"));
    symbols.put("pkg/Foo#bar().", info(SymbolInformation.Kind.Method, "bar"));
    symbols.put("Foo.kt local 1", info(SymbolInformation.Kind.Variable, "localFn"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    Map<String, Object> localCall =
        calls.stream().filter(c -> "local 1".equals(c.get("symbol"))).findFirst().orElse(null);
    assertTrue(localCall != null, "local-callee call site exists");
    assertEquals("localFn", localCall.get("name"), "local callee uses source name, not bare number");
  }

  @Test
  void anonymousObjectMethodIsolatedFromEnclosingFlow() {
    // `bar` 内 `.trailers(object : T { fun peek() = x.peekTrailers() })`。
    // peek 是匿名对象成员（`local 1` 符号）：其方法体应作为独立方法单元，不并进 bar 的 NEXT 链。
    SyntaxTree.Node cls = node("CLASS", 0);
    cls.children.add(node("IDENTIFIER", 1, def("pkg/Foo#", "IdentifierType", 1)));
    SyntaxTree.Node fun = node("FUN", 2);
    fun.children.add(node("IDENTIFIER", 3, def("pkg/Foo#bar().", "IdentifierFunctionDefinition", 3)));
    SyntaxTree.Node body = node("BLOCK", 4);
    SyntaxTree.Node call = node("CALL_EXPRESSION", 5);
    call.children.add(node("OPERATION_REFERENCE", 6, ref("pkg/Foo#trailers().", "IdentifierFunction", 6)));
    SyntaxTree.Node argList = node("VALUE_ARGUMENT_LIST", 7);
    SyntaxTree.Node arg = node("VALUE_ARGUMENT", 8);
    SyntaxTree.Node obj = node("OBJECT_LITERAL", 9);
    SyntaxTree.Node peek = node("FUN", 10);
    peek.children.add(node("IDENTIFIER", 11, def("local 1", "IdentifierFunctionDefinition", 11)));
    SyntaxTree.Node peekBody = node("BLOCK", 12);
    SyntaxTree.Node peekCall = node("CALL_EXPRESSION", 13);
    peekCall.children.add(node("OPERATION_REFERENCE", 14, ref("pkg/Foo#peekTrailers().", "IdentifierFunction", 14)));
    peekBody.children.add(peekCall);
    peek.children.add(peekBody);
    obj.children.add(peek);
    arg.children.add(obj);
    argList.children.add(arg);
    call.children.add(argList);
    body.children.add(call);
    fun.children.add(body);
    cls.children.add(fun);
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/Foo#", info(SymbolInformation.Kind.Class, "Foo"));
    symbols.put("pkg/Foo#bar().", info(SymbolInformation.Kind.Method, "bar"));
    symbols.put("pkg/Foo#trailers().", info(SymbolInformation.Kind.Method, "trailers"));
    symbols.put("pkg/Foo#peekTrailers().", info(SymbolInformation.Kind.Method, "peekTrailers"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    // 匿名 peek 成为独立方法：有 METHOD 节点 + ROOT 锚点。
    String peekId = "kotest::Foo.kt::local 1";
    assertTrue(hasNode(sink, GraphModel.LABEL_METHOD, peekId), "anonymous peek method node");
    assertTrue(hasEdge(sink, GraphModel.REL_ROOT, peekId, null), "anonymous peek has ROOT anchor");

    // peek 体里的 peekTrailers 调用点，不应从主流程 trailers() 直接 NEXT 到达（方法体已隔离）。
    List<Map<String, Object>> calls = nodesOf(sink, GraphModel.LABEL_CALLED_METHOD);
    String trailersId =
        calls.stream()
            .filter(c -> String.valueOf(c.get("symbol")).endsWith("trailers()."))
            .map(c -> (String) c.get("_id"))
            .findFirst()
            .orElse(null);
    String peekTrailersId =
        calls.stream()
            .filter(c -> String.valueOf(c.get("symbol")).endsWith("peekTrailers()."))
            .map(c -> (String) c.get("_id"))
            .findFirst()
            .orElse(null);
    assertTrue(trailersId != null, "trailers call node exists");
    assertTrue(peekTrailersId != null, "peekTrailers call node exists");
    assertFalse(
        hasEdge(sink, GraphModel.REL_NEXT, trailersId, peekTrailersId),
        "anonymous object method body is not inlined into enclosing flow");
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

    // else-if chain: 不再有 ELSE 边（由 NEXT 表达）；else-if 条件节点存在。
    String elseIfCond =
        conditionsOfKind(sink, GraphModel.CONDITION_KIND_IF).stream()
            .filter(c -> ((String) c.get("_id")).contains("Foo.java#15"))
            .findFirst()
            .map(c -> (String) c.get("_id"))
            .orElseThrow();
    assertTrue(!hasEdge(sink, GraphModel.REL_ELSE, ifCond, elseIfCond), "no ELSE edge for else-if");
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

    // Cross-block: the condition is the fork — then 经 NEXT、else 的 ELSE 节点再经 NEXT 进入分支；
    // both branch ends link to the continuation, never skipping via the condition.
    assertTrue(next.test(e0, flag), "e0 before guard read");
    assertTrue(next.test(flag, flagCond), "guard read before condition");
    boolean thenNEXT =
        nexts.stream().anyMatch(e -> flagCond.equals(e.get("_from")) && a.equals(e.get("_to")));
    // else 分支首节点 b 的前置是一个 kind=ELSE 的 Condition 节点(elseB@17)，其从 flagCond 经 ELSE 进入，
    // 而 b 本身经 NEXT 从该 ELSE 节点进入（分支首事件都挂顺序链）。
    String elseCond = "test::Foo.java#17:0:ELSE";
    boolean elseEntry = next.test(flagCond, b);
    assertTrue(thenNEXT, "then branch entered via NEXT from the condition");
    assertTrue(elseEntry, "else branch first event entered via NEXT from the condition");
    assertTrue(next.test(a, c), "then branch end continues after the if");
    assertTrue(next.test(b, c), "else branch end continues after the if");
    assertTrue(!next.test(flagCond, c), "condition must not skip straight to continuation");

    // Branch entry structure: then / else 首事件都经 NEXT 从条件进入；无 SUB/ELSE 边。
    assertTrue(thenNEXT, "then edge entered via NEXT (condition true)");
    assertTrue(elseEntry, "else branch first node entered via NEXT from the condition");

    // 跨函数：NEXT 只在单方法体内建立（不再有 calledMethod→callee 首事件 / callee 退出→calledReturn
    // 这类跨函数 NEXT）。被调 foo 自己的链（Method 根→q）独立成立；调用方 m 的链在调用点后继续
    // （calledReturn→y）。跨函数关联由 CALLS 等逻辑边表达，不再用 NEXT 串起来。
    String calledMethod = "test::Foo.java#20:0";
    String fooRoot = "test::Foo.java#2:0:root"; // callee foo 的 METHOD 根条件（顺序链首事件）
    String q = "test::Foo.java#5:0:FIELD";
    String calledReturn = "test::Foo.java#20:0:CALLED_RETURN";
    String y = "test::Foo.java#25:0:FIELD";
    assertTrue(!next.test(calledMethod, fooRoot), "no cross-function NEXT into callee METHOD-root");
    assertTrue(next.test(fooRoot, q), "callee body starts after METHOD root (within callee)");
    assertTrue(!next.test(q, calledReturn), "no cross-function NEXT from callee exit to calledReturn");
    assertTrue(next.test(calledReturn, y), "caller continues after the call (within caller)");
  }

  @Test
  void withElseIfLastStatementDoesNotLeakConditionToParentNext() {
    // void m() { e0; if (c1) { a0; if (c2) { a1; } else { b1; } } else { d; } c; }
    // 回归：有 else 兜底的 if(c2) 是 c1-then 块的最后一条语句。之前该块退出时把分叉条件 c2
    // 也当作块末端泄漏到父块的下一个事件 c（即 okhttp #56(if responseBuilder==null) 泄漏到
    // #81 requestBody 的问题）。有 else 的 if 分叉应受限：只连 then/else 分支，不再连
    // "整个 if 之后的下一个事件"；只有无 else 的 if 才 fall-through 到下一事件。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    // outer if(c1){ ... } else { d; }
    SyntaxTree.Node c1 = node("IF", 13);
    c1.children.add(node("IDENTIFIER", 14, ref("pkg/A#c1.", "IdentifierConstant", 14)));
    SyntaxTree.Node c1Then = node("BLOCK", 15);
    c1Then.children.add(node("IDENTIFIER", 16, ref("pkg/A#a0.", "IdentifierConstant", 16)));
    // inner with-else if(c2){ a1; } else { b1; } — c1-then 块的最后一条语句
    SyntaxTree.Node c2 = node("IF", 17);
    c2.children.add(node("IDENTIFIER", 18, ref("pkg/A#c2.", "IdentifierConstant", 18)));
    SyntaxTree.Node c2Then = node("BLOCK", 19);
    c2Then.children.add(node("IDENTIFIER", 20, ref("pkg/A#a1.", "IdentifierConstant", 20)));
    c2.children.add(c2Then);
    SyntaxTree.Node c2Else = node("BLOCK", 21);
    c2Else.children.add(node("IDENTIFIER", 22, ref("pkg/A#b1.", "IdentifierConstant", 22)));
    c2.children.add(c2Else);
    c1Then.children.add(c2);
    c1.children.add(c1Then);
    SyntaxTree.Node c1Else = node("BLOCK", 23);
    c1Else.children.add(node("IDENTIFIER", 24, ref("pkg/A#d.", "IdentifierConstant", 24)));
    c1.children.add(c1Else);
    mBody.children.add(c1);

    mBody.children.add(node("IDENTIFIER", 25, ref("pkg/A#c.", "IdentifierConstant", 25)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "c1.", "a0.", "c2.", "a1.", "b1.", "d.", "c."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    java.util.function.BiPredicate<String, String> next =
        (from, to) ->
            edgesOf(sink, GraphModel.REL_NEXT).stream()
                .anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String c1Cond = "test::Foo.java#13:0";
    String c2Cond = "test::Foo.java#17:0";
    String a0 = "test::Foo.java#16:0:FIELD";
    String a1 = "test::Foo.java#20:0:FIELD";
    String b1 = "test::Foo.java#22:0:FIELD";
    String d = "test::Foo.java#24:0:FIELD";
    String c = "test::Foo.java#25:0:FIELD";

    // 有 else 的 if 分叉受限：条件只连 then/else 分支首事件。
    assertTrue(next.test(c1Cond, a0), "outer then branch from condition (true path)");
    assertTrue(next.test(c1Cond, d), "outer else branch from condition (false path)");
    assertTrue(next.test(c2Cond, a1), "inner then branch from condition (true path)");
    assertTrue(next.test(c2Cond, b1), "inner else branch from condition (false path)");
    // 关键回归：有 else 的条件不泄漏到父块的下一个事件。
    assertTrue(!next.test(c1Cond, c), "outer with-else condition must not leak to next event");
    assertTrue(!next.test(c2Cond, c), "inner with-else condition must not leak to next event");
    // 分支尾(真实末端)汇入下一个事件。
    assertTrue(next.test(a1, c), "inner then tail merges at next event");
    assertTrue(next.test(b1, c), "inner else tail merges at next event");
    assertTrue(next.test(d, c), "outer else tail merges at next event");
  }

  @Test
  void ifConditionForksExactlyTwoNextEdges() {
    // void m() { e0; if (c1) { a; } if (c2) { b; } else { d; } end; }
    // 不变量：每个 if 条件节点的 NEXT 出边恒为 2（1 真 + 1 假），不多不少——
    //   - 无 else：真→then 分支首，假→fall-through 到下一事件（否则只有 1 条=“少”，即缺假路径）；
    //   - 有 else：真→then 分支首，假→else 分支首，分叉受限、不再多连下一事件/合并点
    //              （否则 3 条=“多”）。
    // 合并点（分支尾→下一事件）由分支尾承担，不占 if 条件自己的分叉。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    // if (c1) { a; } —— 无 else
    SyntaxTree.Node c1 = node("IF", 13);
    c1.children.add(node("IDENTIFIER", 14, ref("pkg/A#c1.", "IdentifierConstant", 14)));
    SyntaxTree.Node c1Then = node("BLOCK", 15);
    c1Then.children.add(node("IDENTIFIER", 16, ref("pkg/A#a.", "IdentifierConstant", 16)));
    c1.children.add(c1Then);
    mBody.children.add(c1);

    // if (c2) { b; } else { d; } —— 有 else
    SyntaxTree.Node c2 = node("IF", 17);
    c2.children.add(node("IDENTIFIER", 18, ref("pkg/A#c2.", "IdentifierConstant", 18)));
    SyntaxTree.Node c2Then = node("BLOCK", 19);
    c2Then.children.add(node("IDENTIFIER", 20, ref("pkg/A#b.", "IdentifierConstant", 20)));
    c2.children.add(c2Then);
    SyntaxTree.Node c2Else = node("BLOCK", 21);
    c2Else.children.add(node("IDENTIFIER", 22, ref("pkg/A#d.", "IdentifierConstant", 22)));
    c2.children.add(c2Else);
    mBody.children.add(c2);

    mBody.children.add(node("IDENTIFIER", 23, ref("pkg/A#end.", "IdentifierConstant", 23)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "c1.", "a.", "c2.", "b.", "d.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> nextOutCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String c1id = "test::Foo.java#13:0";
    String c2id = "test::Foo.java#17:0";
    String a = "test::Foo.java#16:0:FIELD";
    String b = "test::Foo.java#20:0:FIELD";
    String d = "test::Foo.java#22:0:FIELD";
    String end = "test::Foo.java#23:0:FIELD";
    // 每个 if 条件节点恰好 2 条 NEXT 出边（1 真 + 1 假），不多不少。
    assertEquals(2L, (long) nextOutCount.apply(c1id), "no-else if forks exactly 2 (then + fall-through)");
    assertEquals(2L, (long) nextOutCount.apply(c2id), "with-else if forks exactly 2 (then + else, no more)");
    // 方向语义：真→then 分支首；有 else 的假→else 分支首且不再多连下一事件。
    assertTrue(next.test(c1id, a), "no-else true path -> then branch");
    assertTrue(next.test(c2id, b), "with-else true path -> then branch");
    assertTrue(next.test(c2id, d), "with-else false path -> else branch");
    assertTrue(!next.test(c2id, end), "with-else must not also fork to the following event (no 3rd edge)");
  }

  @Test
  void ifBranchEdgesCarryOneTrueOneFalse() {
    // void m() { if (c) { a; } else { b; } }
    // 不变量：if 条件的两条 NEXT 分支边必然一条 branch="true"(→then 首事件)、一条 branch="false"(→else 首事件)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node ifNode = node("IF", 12);
    ifNode.children.add(node("IDENTIFIER", 13, ref("pkg/A#c.", "IdentifierConstant", 13)));
    SyntaxTree.Node thenB = node("BLOCK", 14);
    thenB.children.add(node("IDENTIFIER", 15, ref("pkg/A#a.", "IdentifierConstant", 15)));
    ifNode.children.add(thenB);
    SyntaxTree.Node elseB = node("BLOCK", 16);
    elseB.children.add(node("IDENTIFIER", 17, ref("pkg/A#b.", "IdentifierConstant", 17)));
    ifNode.children.add(elseB);
    mBody.children.add(ifNode);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "a.", "b."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    String condId = "test::Foo.java#12:0";
    String a = "test::Foo.java#15:0:FIELD";
    String b = "test::Foo.java#17:0:FIELD";
    long outCount = nexts.stream().filter(e -> condId.equals(e.get("_from"))).count();
    assertEquals(2L, outCount, "if condition has exactly 2 NEXT branch edges");
    java.util.function.Function<String, String> branchOf =
        target -> nexts.stream()
            .filter(e -> condId.equals(e.get("_from")) && target.equals(e.get("_to")))
            .map(e -> (String) ((Map<String, Object>) e.get("_props")).get("branch"))
            .findFirst()
            .orElse(null);
    assertEquals("true", branchOf.apply(a), "then branch edge carries branch=true");
    assertEquals("false", branchOf.apply(b), "else branch edge carries branch=false");
  }

  @Test
  void noElseIfFallthroughEdgeCarriesFalse() {
    // void m() { if (c) { a; } end; }
    // 无 else 的 if：条件真→then 首事件(branch="true")；假→fall-through 到 if 之后的下一事件 end，
    // 该汇入边也要带 branch="false"——保证无论有无 else，if 的"真+假"两条 NEXT 都带 branch 属性。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node ifNode = node("IF", 12);
    ifNode.children.add(node("IDENTIFIER", 13, ref("pkg/A#c.", "IdentifierConstant", 13)));
    SyntaxTree.Node thenB = node("BLOCK", 14);
    thenB.children.add(node("IDENTIFIER", 15, ref("pkg/A#a.", "IdentifierConstant", 15)));
    ifNode.children.add(thenB);
    mBody.children.add(ifNode);
    mBody.children.add(node("IDENTIFIER", 20, ref("pkg/A#end.", "IdentifierConstant", 20)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "a.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    String condId = "test::Foo.java#12:0";
    String a = "test::Foo.java#15:0:FIELD";
    String end = "test::Foo.java#20:0:FIELD";
    long outCount = nexts.stream().filter(e -> condId.equals(e.get("_from"))).count();
    assertEquals(2L, outCount, "no-else if forks exactly 2 (then + fall-through)");
    java.util.function.Function<String, String> branchOf =
        target -> nexts.stream()
            .filter(e -> condId.equals(e.get("_from")) && target.equals(e.get("_to")))
            .map(e -> (String) ((Map<String, Object>) e.get("_props")).get("branch"))
            .findFirst()
            .orElse(null);
    assertEquals("true", branchOf.apply(a), "then branch edge carries branch=true");
    assertEquals("false", branchOf.apply(end), "no-else fall-through edge carries branch=false");
  }

  @Test
  void reassignmentWriteDefersPastWhenRhsSoBranchesMergeAtWrite() {
    // fun m() { response = when { g1 -> { A } else -> { B } }; end; }
    // `x = when{…}` 的写应延迟到整个 when RHS 求值完再入链(先读后写)：
    // response 写(原 LHS 行)在 when 里各分支事件(更大行)之前就被创建，若不延迟会写成"先写后读"、
    // 且分支尾无合并点。用赋值节点末行作冲排边界后，写接收 A 尾 + B 尾，再续到 end。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);

    SyntaxTree.Node bin = node("BINARY_EXPRESSION", 12);
    // 赋值节点 span 从 LHS 到 RHS(when)末行(16)，供"写"以语句末行为冲排边界。
    bin.range = new ScipRange(12, 0, 16, 5);
    SyntaxTree.Node lhs = node("REFERENCE_EXPRESSION", 12);
    lhs.children.add(node("IDENTIFIER", 12, ref("pkg/A#response.", "IdentifierLocal", 12)));
    bin.children.add(lhs);
    SyntaxTree.Node opRef = node("OPERATION_REFERENCE", 12);
    opRef.children.add(node("EQ", 12));
    bin.children.add(opRef);

    SyntaxTree.Node when = node("WHEN", 12);
    SyntaxTree.Node entry1 = node("WHEN_ENTRY", 13);
    SyntaxTree.Node guard = node("WHEN_CONDITION_WITH_EXPRESSION", 13);
    guard.children.add(node("IDENTIFIER", 13, ref("pkg/A#g1.", "IdentifierConstant", 13)));
    entry1.children.add(guard);
    entry1.children.add(node("ARROW", 13));
    SyntaxTree.Node aBlock = node("BLOCK", 14);
    aBlock.children.add(node("IDENTIFIER", 14, ref("pkg/A#a.", "IdentifierConstant", 14)));
    entry1.children.add(aBlock);
    when.children.add(entry1);
    SyntaxTree.Node entry2 = node("WHEN_ENTRY", 15);
    entry2.children.add(node("else", 15));
    entry2.children.add(node("ARROW", 15));
    SyntaxTree.Node bBlock = node("BLOCK", 15);
    bBlock.children.add(node("IDENTIFIER", 15, ref("pkg/A#b.", "IdentifierConstant", 15)));
    entry2.children.add(bBlock);
    when.children.add(entry2);
    bin.children.add(when);
    mBody.children.add(bin);
    mBody.children.add(node("IDENTIFIER", 30, ref("pkg/A#end.", "IdentifierConstant", 30)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"response.", "g1.", "a.", "b.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    String response = "test::Foo.java#12:0:LOCAL_VAR";
    String A = "test::Foo.java#14:0:FIELD";
    String B = "test::Foo.java#15:0:FIELD";
    String end = "test::Foo.java#30:0:FIELD";
    assertTrue(hasNode(sink, GraphModel.LABEL_VALUE, response), "reassignment write node exists");
    // 写延迟到 when RHS 之后：A 尾 + B 尾汇入 response 写(合并点)，再续到 end(先读后写)。
    assertTrue(next.test(A, response), "when true-branch tail merges at response write");
    assertTrue(next.test(B, response), "when else-branch tail merges at response write");
    assertTrue(next.test(response, end), "response write continues to next event (merge point in chain)");
  }

  @Test
  void whenExpressionForksLikeIfElseIf() {
    // fun m() { e0; when { g1 -> { A } else -> { B } }; end; }
    // when 恒 2：条件(守卫 g1)真→A 分支首、假→else B 分支首；守卫读 g1 在条件之前；A 尾 + B 尾汇入 end。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node when = node("WHEN", 13);
    // entry1: g1 -> { A }
    SyntaxTree.Node entry1 = node("WHEN_ENTRY", 14);
    SyntaxTree.Node guard = node("WHEN_CONDITION_WITH_EXPRESSION", 14);
    guard.children.add(node("IDENTIFIER", 15, ref("pkg/A#g1.", "IdentifierConstant", 15)));
    entry1.children.add(guard);
    entry1.children.add(node("ARROW", 14));
    SyntaxTree.Node aBlock = node("BLOCK", 16);
    aBlock.children.add(node("IDENTIFIER", 16, ref("pkg/A#a.", "IdentifierConstant", 16)));
    entry1.children.add(aBlock);
    when.children.add(entry1);
    // entry2: else -> { B }
    SyntaxTree.Node entry2 = node("WHEN_ENTRY", 17);
    entry2.children.add(node("else", 17));
    entry2.children.add(node("ARROW", 17));
    SyntaxTree.Node bBlock = node("BLOCK", 18);
    bBlock.children.add(node("IDENTIFIER", 18, ref("pkg/A#b.", "IdentifierConstant", 18)));
    entry2.children.add(bBlock);
    when.children.add(entry2);
    mBody.children.add(when);
    mBody.children.add(node("IDENTIFIER", 19, ref("pkg/A#end.", "IdentifierConstant", 19)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "g1.", "a.", "b.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> nextOutCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> nextInCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String cond = "test::Foo.java#14:0";
    String g1 = "test::Foo.java#15:0:FIELD";
    String A = "test::Foo.java#16:0:FIELD";
    String B = "test::Foo.java#18:0:FIELD";
    String end = "test::Foo.java#19:0:FIELD";
    // when 条件恒 2 分叉：真→A 分支首，假→else B 分支首（不再把守卫读当分支），共 2 条，不是 N 叉。
    assertEquals(2L, (long) nextOutCount.apply(cond), "when condition forks exactly 2");
    assertTrue(next.test(cond, A), "when true path -> first branch body");
    assertTrue(next.test(cond, B), "when false path -> else branch body");
    assertTrue(!next.test(cond, g1), "guard read is not a branch of the when condition");
    // 守卫读在条件之前：g1 → 条件（先读后判定）。
    assertTrue(next.test(g1, cond), "guard read precedes the when condition");
    // 合并点 end 汇入 A 尾 + B 尾（条件不是叶终端，不汇入）。
    assertEquals(2L, (long) nextInCount.apply(end), "when merge (end) joined by A + B tails");
    assertTrue(next.test(A, end), "branch A tail merges");
    assertTrue(next.test(B, end), "branch B tail merges");
    assertTrue(!next.test(cond, end), "when condition is not a terminal at the merge");
  }

  @Test
  void javaSwitchForksLikeKotlinWhen() {
    // void m() { e0; switch (s) { case L1: A; break; default: B; } end; }
    // switch 与 when 对齐：选择器读 s 先入链；每个 case 的条件(标签读)恒 2 分叉
    // (真→case 体首、假→下一 case 条件)；default 从末条件假路径进入；各分支尾汇入 end。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node sw = node("SWITCH", 13);
    // 选择器 s
    sw.children.add(node("IDENTIFIER", 13, ref("pkg/A#s.", "IdentifierConstant", 13)));
    // case L1: A; break;
    SyntaxTree.Node case1 = node("CASE", 14);
    case1.children.add(node("IDENTIFIER", 14, ref("pkg/A#L1.", "IdentifierConstant", 14)));
    SyntaxTree.Node aStmt = node("EXPRESSION_STATEMENT", 15);
    aStmt.children.add(node("IDENTIFIER", 15, ref("pkg/A#a.", "IdentifierConstant", 15)));
    case1.children.add(aStmt);
    case1.children.add(node("BREAK", 15));
    sw.children.add(case1);
    // default: B;
    SyntaxTree.Node case2 = node("CASE", 16);
    case2.children.add(node("DEFAULT", 16));
    SyntaxTree.Node bStmt = node("EXPRESSION_STATEMENT", 17);
    bStmt.children.add(node("IDENTIFIER", 17, ref("pkg/A#b.", "IdentifierConstant", 17)));
    case2.children.add(bStmt);
    sw.children.add(case2);
    mBody.children.add(sw);
    mBody.children.add(node("IDENTIFIER", 19, ref("pkg/A#end.", "IdentifierConstant", 19)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "s.", "L1.", "a.", "b.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> nextOutCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> nextInCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    // case L1 的条件节点在 L1 标签行(14)。
    String cond1 = "test::Foo.java#14:0";
    String s = "test::Foo.java#13:0:FIELD";
    String L1 = "test::Foo.java#14:0:FIELD";
    String A = "test::Foo.java#15:0:FIELD";
    String B = "test::Foo.java#17:0:FIELD";
    String end = "test::Foo.java#19:0:FIELD";

    // 选择器读先入链（先求值），再是标签读，然后才是 case 条件节点。
    assertTrue(next.test(s, L1), "switch selector read precedes the case label read");
    // 标签读先入链：L1 → case 条件
    assertTrue(next.test(L1, cond1), "case label read precedes the case condition");
    // case 条件恒 2 分叉：真→case 体首 A，假→default 分支首 B
    assertEquals(2L, (long) nextOutCount.apply(cond1), "switch case condition forks exactly 2");
    assertTrue(next.test(cond1, A), "case true path -> case body");
    assertTrue(next.test(cond1, B), "case false path -> default branch");
    assertTrue(!next.test(cond1, L1), "case label read is not a branch target");
    // 合并点 end 汇入 A 尾 + B 尾
    assertTrue(next.test(A, end), "case body tail merges into after-switch");
    assertTrue(next.test(B, end), "default body tail merges into after-switch");
  }

  @Test
  void javaSwitchFallThroughChainsToNextCaseAndBreakDoesNot() {
    // void m() { switch (s) { case L1: A; case L2: B; break; } end; }
    // L1 体以普通语句 A 结尾 → fall-through：A 尾 NEXT 到 L2 体首 B（而非汇入 end）。
    // L2 体以 break 结尾 → 不 fall-through：B 尾汇入 end。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node sw = node("SWITCH", 13);
    sw.children.add(node("IDENTIFIER", 13, ref("pkg/A#s.", "IdentifierConstant", 13)));
    // case L1: A;   (无 break → fall-through)
    SyntaxTree.Node case1 = node("CASE", 14);
    case1.children.add(node("IDENTIFIER", 14, ref("pkg/A#L1.", "IdentifierConstant", 14)));
    SyntaxTree.Node aStmt = node("EXPRESSION_STATEMENT", 15);
    aStmt.children.add(node("IDENTIFIER", 15, ref("pkg/A#a.", "IdentifierConstant", 15)));
    case1.children.add(aStmt);
    sw.children.add(case1);
    // case L2: B; break;   (有 break → 不 fall-through)
    SyntaxTree.Node case2 = node("CASE", 16);
    case2.children.add(node("IDENTIFIER", 16, ref("pkg/A#L2.", "IdentifierConstant", 16)));
    SyntaxTree.Node bStmt = node("EXPRESSION_STATEMENT", 17);
    bStmt.children.add(node("IDENTIFIER", 17, ref("pkg/A#b.", "IdentifierConstant", 17)));
    case2.children.add(bStmt);
    case2.children.add(node("BREAK", 17));
    sw.children.add(case2);
    mBody.children.add(sw);
    mBody.children.add(node("IDENTIFIER", 19, ref("pkg/A#end.", "IdentifierConstant", 19)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "s.", "L1.", "L2.", "a.", "b.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String A = "test::Foo.java#15:0:FIELD";
    String B = "test::Foo.java#17:0:FIELD";
    String end = "test::Foo.java#19:0:FIELD";
    // L1 无 break → fall-through：A 尾 → L2 体首 B
    assertTrue(next.test(A, B), "case without break falls through to next case body");
    // A 尾不汇入 end（它坠入了下一个 case）
    assertTrue(!next.test(A, end), "fall-through case tail does NOT merge after switch");
    // L2 有 break → 不 fall-through：B 尾汇入 end
    assertTrue(next.test(B, end), "case ending in break merges after switch");
  }

  /** 建一个带单个标签与若干语句的 CASE（stmts 为符号短名，行号自增；brk 决定末尾是否加 BREAK）。 */
  private static SyntaxTree.Node caseNode(int line, String labelSym, String[] stmts, boolean brk) {
    SyntaxTree.Node c = node("CASE", line);
    if (labelSym != null) {
      c.children.add(node("IDENTIFIER", line, ref("pkg/A#" + labelSym, "IdentifierConstant", line)));
    } else {
      c.children.add(node("DEFAULT", line));
    }
    int l = line + 1;
    for (String s : stmts) {
      SyntaxTree.Node st = node("EXPRESSION_STATEMENT", l);
      st.children.add(node("IDENTIFIER", l, ref("pkg/A#" + s, "IdentifierConstant", l)));
      c.children.add(st);
      l++;
    }
    if (brk) c.children.add(node("BREAK", l));
    return c;
  }

  /** 把若干 CASE 组装进 `void m() { e0; switch (s) { … } end; }`，跑完后返回本次的 sink。 */
  private static MemorySink runSwitchSink(SyntaxTree.Node[] caseNodes, String[] fieldNames) {
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));
    SyntaxTree.Node s = node("SWITCH", 13);
    s.children.add(node("IDENTIFIER", 13, ref("pkg/A#s.", "IdentifierConstant", 13)));
    for (SyntaxTree.Node c : caseNodes) s.children.add(c);
    mBody.children.add(s);
    mBody.children.add(node("IDENTIFIER", 90, ref("pkg/A#end.", "IdentifierConstant", 90)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : fieldNames) symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();
    return sink;
  }

  /** 同 {@link #runSwitchSink}，但返回 NEXT 边判定器。 */
  private static java.util.function.BiPredicate<String, String> runSwitch(
      SyntaxTree.Node[] caseNodes, String[] fieldNames) {
    MemorySink sink = runSwitchSink(caseNodes, fieldNames);
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    return (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
  }

  @Test
  void javaSwitchFallThroughSkipsEmptyCases() {
    // switch (s) { case L1: A; case L2: case L3: C; break; }
    // L2 是空体（合并标签）：L1 的坠落边应穿过它直达 L3 的体首 C，而不是停在中途或汇入 end。
    java.util.function.BiPredicate<String, String> next =
        runSwitch(
            new SyntaxTree.Node[] {
              caseNode(14, "L1", new String[] {"a"}, false),
              caseNode(16, "L2", new String[] {}, false),
              caseNode(17, "L3", new String[] {"c"}, true)
            },
            new String[] {"e0.", "s.", "L1.", "L2.", "L3.", "a.", "c.", "end."});

    String A = "test::Foo.java#15:0:FIELD";
    String C = "test::Foo.java#18:0:FIELD";
    String end = "test::Foo.java#90:0:FIELD";
    assertTrue(next.test(A, C), "fall-through skips empty case and lands on next non-empty body");
    assertTrue(!next.test(A, end), "fall-through tail does not merge after switch");
    assertTrue(next.test(C, end), "break case merges after switch");
  }

  @Test
  void javaSwitchFallThroughFromNonEmptyToNextCase() {
    // switch (s) { case L1: case L2: A; case L3: C; break; }
    // L2 体无 break → A 坠到 L3 体首 C；且不得产生 A→A 自环。
    java.util.function.BiPredicate<String, String> next =
        runSwitch(
            new SyntaxTree.Node[] {
              caseNode(14, "L1", new String[] {}, false),
              caseNode(15, "L2", new String[] {"a"}, false),
              caseNode(17, "L3", new String[] {"c"}, true)
            },
            new String[] {"e0.", "s.", "L1.", "L2.", "L3.", "a.", "c.", "end."});

    String A = "test::Foo.java#16:0:FIELD";
    String C = "test::Foo.java#18:0:FIELD";
    String end = "test::Foo.java#90:0:FIELD";
    assertTrue(next.test(A, C), "fall-through chains to next case body");
    assertTrue(!next.test(A, A), "fall-through must not create a self edge");
    assertTrue(!next.test(A, end), "fall-through tail does not merge after switch");
    assertTrue(next.test(C, end), "break case merges after switch");
  }

  @Test
  void javaSwitchMergedLabelCaseHasTruePathIntoNextBody() {
    // switch (s) { case L1: case L2: B; break; }
    // L1 是空体（合并标签）：命中 L1 等价于命中 L2，故 L1 折叠进 L2——只有一个条件节点，
    // 其真路径必须进入 B（空体若建条件节点，真路径会悬挂无出边）。
    MemorySink sink =
        runSwitchSink(
            new SyntaxTree.Node[] {
              caseNode(14, "L1", new String[] {}, false),
              caseNode(16, "L2", new String[] {"b"}, true)
            },
            new String[] {"e0.", "s.", "L1.", "L2.", "b.", "end."});
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String cond = "test::Foo.java#16:0";
    String B = "test::Foo.java#17:0:FIELD";
    // 空体的 L1 不建自己的条件节点（每一行至多一个条件节点）；若建了，其真路径会悬挂无出边。
    assertEquals(1, conditionsOfKind(sink, GraphModel.CONDITION_KIND_IF).size(),
        "empty merged-label case creates no dangling condition of its own");
    assertTrue(next.test(cond, B), "merged label's condition true path enters the shared body");
  }

  @Test
  void javaSwitchConditionalAbruptStillFallsThrough() {
    // switch (s) { case L1: A; if (c) return; case L2: B; break; }
    // 末条 if(c) return 只在 c 为真时离开：c 为假仍坠入 L2，故本条应 fall-through（假路径 → B）。
    SyntaxTree.Node case1 = node("CASE", 14);
    case1.children.add(node("IDENTIFIER", 14, ref("pkg/A#L1.", "IdentifierConstant", 14)));
    SyntaxTree.Node aStmt = node("EXPRESSION_STATEMENT", 15);
    aStmt.children.add(node("IDENTIFIER", 15, ref("pkg/A#a.", "IdentifierConstant", 15)));
    case1.children.add(aStmt);
    SyntaxTree.Node iff = node("IF", 16);
    iff.children.add(node("IDENTIFIER", 16, ref("pkg/A#c.", "IdentifierConstant", 16)));
    iff.children.add(node("RETURN", 16));
    case1.children.add(iff);

    java.util.function.BiPredicate<String, String> next =
        runSwitch(
            new SyntaxTree.Node[] {case1, caseNode(17, "L2", new String[] {"b"}, true)},
            new String[] {"e0.", "s.", "L1.", "L2.", "c.", "a.", "b.", "end."});

    String cond = "test::Foo.java#16:0";
    String B = "test::Foo.java#18:0:FIELD";
    String end = "test::Foo.java#90:0:FIELD";
    assertTrue(next.test(cond, B), "conditional abrupt leaves a fall-through path to the next case");
    assertTrue(!next.test(cond, end), "conditional abrupt must not be treated as always-abrupt");
  }

  @Test
  void javaSwitchAllBranchesAbruptIsNotFallThrough() {
    // switch (s) { case L1: if (c) return; else throw; case L2: B; break; }
    // 两条分支都 abrupt 且无隐式出口 → 该 case 必然离开，不坠落（不应连到 L2）。
    SyntaxTree.Node case1 = node("CASE", 14);
    case1.children.add(node("IDENTIFIER", 14, ref("pkg/A#L1.", "IdentifierConstant", 14)));
    SyntaxTree.Node iff = node("IF", 15);
    iff.children.add(node("IDENTIFIER", 15, ref("pkg/A#cl1.", "IdentifierConstant", 15)));
    iff.children.add(node("RETURN", 15));
    iff.children.add(node("THROW", 15));
    case1.children.add(iff);

    java.util.function.BiPredicate<String, String> next =
        runSwitch(
            new SyntaxTree.Node[] {case1, caseNode(17, "L2", new String[] {"b"}, true)},
            new String[] {"e0.", "s.", "L1.", "L2.", "cl1.", "b.", "end."});

    String L2 = "test::Foo.java#17:0:FIELD";
    String B = "test::Foo.java#18:0:FIELD";
    assertTrue(!next.test(L2, B), "always-abrupt case (if/else both abrupt) does not fall through");
  }

  @Test
  void nestedWithElseIfAsLastStatementFansTailsIntoOuterMerge() {
    // void m() { e0; if (c1) { X; if (c2) { A } else { B } } else { D } end; }
    // 内层有 else 的 if(c2) 是 c1-then 的最后一条语句：它在本块内没有"之后的事件"，故不在块内
    // 单独设合流点；其两条分支链尾 A/B 作为 c1-then 的末端上汇到外层合流点 end。c2 条件(有 else)
    // 不是叶终端、不汇入 end。=> end 汇入 3 条叶终端：A(内层真尾)、B(内层假尾)、D(外层 else 尾)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node c1 = node("IF", 13);
    c1.children.add(node("IDENTIFIER", 14, ref("pkg/A#c1.", "IdentifierConstant", 14)));
    SyntaxTree.Node c1Then = node("BLOCK", 15);
    c1Then.children.add(node("IDENTIFIER", 16, ref("pkg/A#X.", "IdentifierConstant", 16)));
    SyntaxTree.Node c2 = node("IF", 17);
    c2.children.add(node("IDENTIFIER", 18, ref("pkg/A#c2.", "IdentifierConstant", 18)));
    SyntaxTree.Node c2Then = node("BLOCK", 19);
    c2Then.children.add(node("IDENTIFIER", 20, ref("pkg/A#A.", "IdentifierConstant", 20)));
    c2.children.add(c2Then);
    SyntaxTree.Node c2Else = node("BLOCK", 21);
    c2Else.children.add(node("IDENTIFIER", 22, ref("pkg/A#B.", "IdentifierConstant", 22)));
    c2.children.add(c2Else);
    c1Then.children.add(c2);
    c1.children.add(c1Then);
    SyntaxTree.Node c1Else = node("BLOCK", 23);
    c1Else.children.add(node("IDENTIFIER", 24, ref("pkg/A#D.", "IdentifierConstant", 24)));
    c1.children.add(c1Else);
    mBody.children.add(c1);
    mBody.children.add(node("IDENTIFIER", 25, ref("pkg/A#end.", "IdentifierConstant", 25)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "c1.", "X.", "c2.", "A.", "B.", "D.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> inCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String c1id = "test::Foo.java#13:0";
    String c2id = "test::Foo.java#17:0";
    String X = "test::Foo.java#16:0:FIELD";
    String A = "test::Foo.java#20:0:FIELD";
    String B = "test::Foo.java#22:0:FIELD";
    String D = "test::Foo.java#24:0:FIELD";
    String end = "test::Foo.java#25:0:FIELD";
    // 内层 / 外层各自恒 2 分叉。
    assertEquals(2L, (long) outCount.apply(c2id), "inner if forks 2");
    assertEquals(2L, (long) outCount.apply(c1id), "outer if forks 2");
    assertTrue(next.test(c2id, A), "inner true -> then branch");
    assertTrue(next.test(c2id, B), "inner false -> else branch");
    assertTrue(next.test(c1id, X), "outer true -> then branch");
    assertTrue(next.test(c1id, D), "outer false -> else branch");
    // 内层条件(有 else)与外层条件都不是叶终端，不汇入 end。
    assertTrue(!next.test(c2id, end), "inner with-else condition is not a terminal at outer merge");
    assertTrue(!next.test(c1id, end), "outer condition is not a terminal at its own merge");
    // 合流点 end 汇入 3 条叶终端：内层真尾 + 内层假尾 + 外层 else 尾。
    assertEquals(3L, (long) inCount.apply(end), "outer merge joined by inner-if's 2 tails + outer else tail");
    assertTrue(next.test(A, end), "inner then tail merges at outer continuation");
    assertTrue(next.test(B, end), "inner else tail merges at outer continuation");
    assertTrue(next.test(D, end), "outer else tail merges at outer continuation");
  }

  @Test
  void noElseNestedIfConditionIsFallthroughTerminal() {
    // void m() { e0; if (c1) { X; if (c2) { A } } else { D } end; }
    // 内层无 else 的 if(c2) 是 c1-then 最后一条：其条件作为假路径 fall-through 叶终端汇入 end。
    // 合流点 end 汇入 A(真尾)、c2(条件 fall-through)、D(外层 else 尾)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node c1 = node("IF", 13);
    c1.children.add(node("IDENTIFIER", 14, ref("pkg/A#c1.", "IdentifierConstant", 14)));
    SyntaxTree.Node c1Then = node("BLOCK", 15);
    c1Then.children.add(node("IDENTIFIER", 16, ref("pkg/A#X.", "IdentifierConstant", 16)));
    SyntaxTree.Node c2 = node("IF", 17);
    c2.children.add(node("IDENTIFIER", 18, ref("pkg/A#c2.", "IdentifierConstant", 18)));
    SyntaxTree.Node c2Then = node("BLOCK", 19);
    c2Then.children.add(node("IDENTIFIER", 20, ref("pkg/A#A.", "IdentifierConstant", 20)));
    c2.children.add(c2Then);
    c1Then.children.add(c2);
    c1.children.add(c1Then);
    SyntaxTree.Node c1Else = node("BLOCK", 23);
    c1Else.children.add(node("IDENTIFIER", 24, ref("pkg/A#D.", "IdentifierConstant", 24)));
    c1.children.add(c1Else);
    mBody.children.add(c1);
    mBody.children.add(node("IDENTIFIER", 25, ref("pkg/A#end.", "IdentifierConstant", 25)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "c1.", "X.", "c2.", "A.", "D.", "end."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> inCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String c1id = "test::Foo.java#13:0";
    String c2id = "test::Foo.java#17:0";
    String A = "test::Foo.java#20:0:FIELD";
    String D = "test::Foo.java#24:0:FIELD";
    String end = "test::Foo.java#25:0:FIELD";
    assertEquals(2L, (long) outCount.apply(c2id), "inner no-else if forks 2 (true + fall-through)");
    assertTrue(next.test(c2id, A), "inner true -> then branch");
    assertTrue(next.test(c2id, end), "inner no-else condition is the fall-through terminal into end");
    // 合流点 end 汇入 A(真尾)、c2(条件 fall-through)、D(外层 else 尾)。
    assertEquals(3L, (long) inCount.apply(end), "merge joined by then tail + no-else condition + outer else tail");
    assertTrue(next.test(A, end), "then tail merges at outer continuation");
    assertTrue(next.test(D, end), "outer else tail merges at outer continuation");
  }

  @Test
  void whileLoopFormsNextCycleWithCondExit() {
    // void m() { while (c) { body } next; }
    // 循环条件恒 2 分叉：真→body 首事件；假→next(退出)。body 末端回边到条件，形成 NEXT 环；
    // next 只从条件(假路径)汇入。守卫读 c → 条件。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node wh = node("WHILE", 13);
    wh.children.add(node("IDENTIFIER", 14, ref("pkg/A#c.", "IdentifierConstant", 14)));
    SyntaxTree.Node bodyBlock = node("BLOCK", 15);
    bodyBlock.children.add(node("IDENTIFIER", 16, ref("pkg/A#body.", "IdentifierConstant", 16)));
    wh.children.add(bodyBlock);
    mBody.children.add(wh);
    mBody.children.add(node("IDENTIFIER", 17, ref("pkg/A#next.", "IdentifierConstant", 17)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "body.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> inCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String whId = "test::Foo.java#13:0";
    String c = "test::Foo.java#14:0:FIELD";
    String body = "test::Foo.java#16:0:FIELD";
    String nextNode = "test::Foo.java#17:0:FIELD";
    // 循环条件恒 2 分叉：真→body，假→next(退出)。
    assertEquals(2L, (long) outCount.apply(whId), "loop condition forks exactly 2");
    assertTrue(next.test(whId, body), "loop condition true path -> body first event");
    assertTrue(next.test(whId, nextNode), "loop condition false path -> next (exit)");
    // 守卫读 → 条件；body 末端回边到条件(形成 NEXT 环)。
    assertTrue(next.test(c, whId), "guard read -> loop condition node");
    assertTrue(next.test(body, c), "body last event loops back to the loop condition guard event");
    // next 只从条件的假路径汇入(1 条)，因为 body 回环、不再线性续到 next。
    assertEquals(1L, (long) inCount.apply(nextNode), "loop exit next joined only by the condition false path");
  }

  @Test
  void javaWhileLoopFormsNextCycleWithCondExit() {
    // javac 版 while：WHILE_LOOP 子节点 = [条件, body]。与 Kotlin WHILE 同一套共享逻辑，
    // 验证 loop 的恒 2 分叉 + body 回边成环 + 条件假路径退出对 Java 索引同样生效。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node wh = node("WHILE_LOOP", 13);
    wh.children.add(node("IDENTIFIER", 14, ref("pkg/A#c.", "IdentifierConstant", 14)));
    SyntaxTree.Node bodyBlock = node("BLOCK", 15);
    bodyBlock.children.add(node("IDENTIFIER", 16, ref("pkg/A#body.", "IdentifierConstant", 16)));
    wh.children.add(bodyBlock);
    mBody.children.add(wh);
    mBody.children.add(node("IDENTIFIER", 17, ref("pkg/A#next.", "IdentifierConstant", 17)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "body.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.Function<String, Long> inCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_to"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String whId = "test::Foo.java#13:0";
    String c = "test::Foo.java#14:0:FIELD";
    String body = "test::Foo.java#16:0:FIELD";
    String nextNode = "test::Foo.java#17:0:FIELD";
    assertEquals(2L, (long) outCount.apply(whId), "javac while loop condition forks exactly 2");
    assertTrue(next.test(whId, body), "while true -> body");
    assertTrue(next.test(whId, nextNode), "while false -> next (exit)");
    assertTrue(next.test(c, whId), "guard read -> loop condition");
    assertTrue(next.test(body, c), "body loops back to loop condition");
    assertEquals(1L, (long) inCount.apply(nextNode), "while exit next joined only by condition false path");
  }

  @Test
  void forLoopFormsNextCycleWithCondExit() {
    // void m() { for (c) { body } next; }
    // for 的条件恒 2 分叉(真→body,假→next 退出),body 末端回边到条件(有 update 则应经 update,此处简化)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node fr = node("FOR", 13);
    fr.children.add(node("IDENTIFIER", 14, ref("pkg/A#c.", "IdentifierConstant", 14)));
    SyntaxTree.Node bodyBlock = node("BLOCK", 15);
    bodyBlock.children.add(node("IDENTIFIER", 16, ref("pkg/A#body.", "IdentifierConstant", 16)));
    fr.children.add(bodyBlock);
    mBody.children.add(fr);
    mBody.children.add(node("IDENTIFIER", 17, ref("pkg/A#next.", "IdentifierConstant", 17)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"c.", "body.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String frId = "test::Foo.java#13:0";
    String body = "test::Foo.java#16:0:FIELD";
    String c = "test::Foo.java#14:0:FIELD";
    String nextNode = "test::Foo.java#17:0:FIELD";
    assertEquals(2L, (long) outCount.apply(frId), "for-loop condition forks exactly 2");
    assertTrue(next.test(frId, body), "for condition true -> body");
    assertTrue(next.test(frId, nextNode), "for condition false -> next (exit)");
    assertTrue(next.test(body, c), "for body last event loops back to the condition guard event");
  }

  @Test
  void doWhileLoopFormsNextCycleWithCondExit() {
    // void m() { do { body } while (c); next; }
    // do-while:主体先执行、条件在末。条件恒 2 分叉(真→body，假→next 退出)；body 末端回边到条件。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node dw = node("DO_WHILE", 13);
    SyntaxTree.Node bodyBlock = node("BLOCK", 14);
    bodyBlock.children.add(node("IDENTIFIER", 15, ref("pkg/A#body.", "IdentifierConstant", 15)));
    dw.children.add(bodyBlock);
    dw.children.add(node("IDENTIFIER", 16, ref("pkg/A#c.", "IdentifierConstant", 16)));
    mBody.children.add(dw);
    mBody.children.add(node("IDENTIFIER", 17, ref("pkg/A#next.", "IdentifierConstant", 17)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"body.", "c.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.Function<String, Long> outCount =
        id -> nexts.stream().filter(e -> id.equals(e.get("_from"))).count();
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String dwId = "test::Foo.java#13:0";
    String body = "test::Foo.java#15:0:FIELD";
    String c = "test::Foo.java#16:0:FIELD";
    String nextNode = "test::Foo.java#17:0:FIELD";
    assertEquals(2L, (long) outCount.apply(dwId), "do-while condition forks exactly 2");
    assertTrue(next.test(dwId, body), "do-while condition true -> body");
    assertTrue(next.test(dwId, nextNode), "do-while condition false -> next (exit)");
    assertTrue(next.test(body, c), "do-while body last event loops back to the condition guard event");
  }

  @Test
  void whileLoopPredicateConditionControlsFromCallReturn() {
    // void m() { while (shouldIgnore(code)) { body } next; }
    // 回归：loop 条件是谓词调用 `shouldIgnore(code)` 时，守卫值是调用返回(CALLED_RETURN)，
    // 而非实参读 `code`——CONTROLS 应从 CALLED_RETURN 指向 loop，回边(body 尾)也应指向 CALLED_RETURN。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node wh = node("WHILE", 13);
    SyntaxTree.Node call = node("CALL_EXPRESSION", 14);
    call.children.add(node("OPERATION_REFERENCE", 15, ref("pkg/A#shouldIgnore().", "IdentifierFunction", 15)));
    SyntaxTree.Node args = node("VALUE_ARGUMENT_LIST", 16);
    SyntaxTree.Node va = node("VALUE_ARGUMENT", 17);
    va.children.add(node("IDENTIFIER", 18, ref("pkg/A#code.", "IdentifierConstant", 18)));
    args.children.add(va);
    call.children.add(args);
    wh.children.add(call);
    SyntaxTree.Node bodyBlock = node("BLOCK", 19);
    bodyBlock.children.add(node("IDENTIFIER", 20, ref("pkg/A#body.", "IdentifierConstant", 20)));
    wh.children.add(bodyBlock);
    mBody.children.add(wh);
    mBody.children.add(node("IDENTIFIER", 21, ref("pkg/A#next.", "IdentifierConstant", 21)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#shouldIgnore().", info(SymbolInformation.Kind.Method, "shouldIgnore"));
    for (String f : new String[] {"code.", "body.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String loopId = "test::Foo.java#13:0";
    String callReturnId = "test::Foo.java#14:0:CALLED_RETURN";
    String codeId = "test::Foo.java#18:0:FIELD";
    String body = "test::Foo.java#20:0:FIELD";
    String nextNode = "test::Foo.java#21:0:FIELD";
    // 守卫值 = 谓词调用返回，非实参读 code。
    assertTrue(hasEdge(sink, GraphModel.REL_CONTROLS, callReturnId, loopId), "CONTROLS from the predicate CALLED_RETURN");
    assertTrue(!hasEdge(sink, GraphModel.REL_CONTROLS, codeId, loopId), "CONTROLS must NOT come from the argument read `code`");
    // 回边指向条件句首事件(第一个值读 code)，而非 CALLED_RETURN 或 LOOP 节点。
    assertTrue(next.test(body, codeId), "body last event loops back to the condition's first event (arg read `code`)");
    // loop 恒 2：真→body，假→next(退出)。
    assertTrue(next.test(loopId, body), "loop condition true -> body");
    assertTrue(next.test(loopId, nextNode), "loop condition false -> next (exit)");
  }

  @Test
  void kotlinIfConditionProducesControls() {
    // void m() { if (flag) { a; } else { b; } }
    // Kotlin 的 if 表达式:IF 节点的首子节点是 `if` 关键字(IF_KEYWORD),条件表达式紧随其后。
    // 回归:conditionExpression 若把关键字当条件表达式,取不到守卫、CONTROLS 边缺失(Java IF
    // 首子节点即条件,不受影响)。应跳过 IF_KEYWORD 取 flag,生成 flag->IF。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);
    SyntaxTree.Node iff = node("IF", 13);
    iff.children.add(node("IF_KEYWORD", 13));
    iff.children.add(node("IDENTIFIER", 14, ref("pkg/A#flag.", "IdentifierConstant", 14)));
    SyntaxTree.Node thenB = node("BLOCK", 15);
    thenB.children.add(node("IDENTIFIER", 16, ref("pkg/A#a.", "IdentifierConstant", 16)));
    iff.children.add(thenB);
    SyntaxTree.Node elseB = node("BLOCK", 17);
    elseB.children.add(node("IDENTIFIER", 18, ref("pkg/A#b.", "IdentifierConstant", 18)));
    iff.children.add(elseB);
    mBody.children.add(iff);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"flag.", "a.", "b."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    String flagId = "test::Foo.java#14:0:FIELD";
    String ifCond = "test::Foo.java#13:0";
    assertTrue(hasEdge(sink, GraphModel.REL_CONTROLS, flagId, ifCond), "CONTROLS from the condition value `flag` to the Kotlin IF");
  }

  @Test
  void orderChainDoesNotForkFromOneNodeAcrossSiblingBlocks() {
    // void m() { e0; {x} {y} {p} {q} c; }（4 个兄弟裸块——TRY/CATCH 现已是条件节点，这里用通用兄弟块回归）
    // 回归：兄弟嵌套块不能都从同一条链尾 e0 上各出 NEXT 分叉，而应按源序线性续接：e0→x→y→p→q→c，
    // e0 仅应有一条出边。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 10, def("pkg/A#m().", "IdentifierFunctionDefinition", 10));
    SyntaxTree.Node mBody = node("BLOCK", 11);

    mBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#e0.", "IdentifierConstant", 12)));

    SyntaxTree.Node b1 = node("BLOCK", 14);
    b1.children.add(node("IDENTIFIER", 15, ref("pkg/A#x.", "IdentifierConstant", 15)));
    mBody.children.add(b1);
    SyntaxTree.Node b2 = node("BLOCK", 17);
    b2.children.add(node("IDENTIFIER", 18, ref("pkg/A#y.", "IdentifierConstant", 18)));
    mBody.children.add(b2);
    SyntaxTree.Node b3 = node("BLOCK", 20);
    b3.children.add(node("IDENTIFIER", 21, ref("pkg/A#p.", "IdentifierConstant", 21)));
    mBody.children.add(b3);
    SyntaxTree.Node b4 = node("BLOCK", 23);
    b4.children.add(node("IDENTIFIER", 24, ref("pkg/A#q.", "IdentifierConstant", 24)));
    mBody.children.add(b4);

    mBody.children.add(node("IDENTIFIER", 25, ref("pkg/A#c.", "IdentifierConstant", 25)));

    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "x.", "y.", "p.", "q.", "c."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next = (from, to) ->
        nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));

    String e0 = "test::Foo.java#12:0:FIELD";
    String x = "test::Foo.java#15:0:FIELD";
    String y = "test::Foo.java#18:0:FIELD";
    String p = "test::Foo.java#21:0:FIELD";
    String q = "test::Foo.java#24:0:FIELD";
    String c = "test::Foo.java#25:0:FIELD";

    long e0Out = nexts.stream().filter(e -> e0.equals(e.get("_from"))).count();
    assertEquals(1, e0Out, "e0 must have exactly one outgoing NEXT (into the first try body)");
    assertTrue(next.test(e0, x), "e0 -> first try body first event");

    // 兄弟块按源序线性续接，不绕过中间块直接回到旧链尾。
    assertTrue(next.test(x, y), "try body end continues into the catch body");
    assertTrue(next.test(y, p), "catch body end continues into the second try body");
    assertTrue(next.test(p, q), "second try body end continues into the second catch body");
    assertTrue(next.test(q, c), "second catch body end continues to the final event");

    // 原 bug：e0 被当作所有兄弟块的共同前置而直接分叉到 y/p/q。
    assertTrue(!next.test(e0, y), "e0 must not fork into the catch body");
    assertTrue(!next.test(e0, p), "e0 must not fork into the second try body");
    assertTrue(!next.test(e0, q), "e0 must not fork into the second catch body");
  }

  @Test
  void elseEdgeBelongsToOwningIfNotNestedCondition() {
    // void m(){ if(flag){ if(x){ q; } } else { s; } }
    // 回归：外层 IF(flag) 的 else 分支入口必须从外层 IF 自身(#10:0)经 NEXT 进入，而不能被 then
    // 分支里嵌套的内层 IF(x) 抢占——否则外层真正有 else 却分不到分支入口(NEXT 指向 s)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);

    SyntaxTree.Node outerIf = node("IF", 10);
    outerIf.children.add(node("IDENTIFIER", 11, ref("pkg/A#flag.", "IdentifierConstant", 11)));
    SyntaxTree.Node thenB = node("BLOCK", 12);
    SyntaxTree.Node innerIf = node("IF", 13);
    innerIf.children.add(node("IDENTIFIER", 14, ref("pkg/A#x.", "IdentifierConstant", 14)));
    SyntaxTree.Node innerThen = node("BLOCK", 15);
    innerThen.children.add(node("IDENTIFIER", 16, ref("pkg/A#q.", "IdentifierConstant", 16)));
    innerIf.children.add(innerThen);
    thenB.children.add(innerIf);
    outerIf.children.add(thenB);
    SyntaxTree.Node elseB = node("BLOCK", 17);
    elseB.children.add(node("IDENTIFIER", 18, ref("pkg/A#s.", "IdentifierConstant", 18)));
    outerIf.children.add(elseB);
    mBody.children.add(outerIf);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"flag.", "x.", "q.", "s."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    List<Map<String, Object>> elseEdges = edgesOf(sink, GraphModel.REL_ELSE);
    String outerIfId = "test::Foo.java#10:0"; // 外层 IF(flag)
    String innerIfId = "test::Foo.java#13:0"; // then 分支里的内层 IF(x)
    String s = "test::Foo.java#18:0:FIELD";   // else 分支首事件
    java.util.function.BiPredicate<String, String> nextFrom = (from, to) ->
        nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    assertTrue(nextFrom.test(outerIfId, s), "outer IF owns its else-branch entry via NEXT");
    assertTrue(!nextFrom.test(innerIfId, s), "inner IF must not steal the outer else-branch entry");
    assertTrue(elseEdges.isEmpty(), "no ELSE edges remain");
  }

  @Test
  void singleBranchIfFallsThroughToNextEvent() {
    // void m(){ if (x) { q; } r; }   —— 单分支、无 else 的 if
    // 条件为假时应直落到 if 之后的下一个事件 r：ifCond --NEXT--> q(真) 且 ifCond --NEXT--> r(假)。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    SyntaxTree.Node ifNode = node("IF", 10);
    ifNode.children.add(node("IDENTIFIER", 11, ref("pkg/A#x.", "IdentifierConstant", 11)));
    SyntaxTree.Node thenB = node("BLOCK", 12);
    thenB.children.add(node("IDENTIFIER", 13, ref("pkg/A#q.", "IdentifierConstant", 13)));
    ifNode.children.add(thenB);
    mBody.children.add(ifNode);
    mBody.children.add(node("IDENTIFIER", 20, ref("pkg/A#r.", "IdentifierConstant", 20)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"x.", "q.", "r."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> nextFrom = (from, to) ->
        nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    String ifCond = "test::Foo.java#10:0";
    String q = "test::Foo.java#13:0:FIELD";
    String r = "test::Foo.java#20:0:FIELD";
    assertTrue(nextFrom.test(ifCond, q), "ifCond -> then-branch first event (true path)");
    assertTrue(nextFrom.test(ifCond, r), "ifCond -> next event after the if (false-path fall-through)");
    assertTrue(nextFrom.test(q, r), "then-branch end continues to the next event");
  }

  @Test
  void tryCatchBecomesConditionNodesLikeIfElse() {
    // void m(){ try { a; } catch (e) { b; } }
    // TRY 物化为 kind=TRY 条件节点、CATCH 物化为 kind=CATCH 条件节点，且 `TRY --ELSE--> CATCH`（SUB 到 TRY）。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);

    mBody.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9))); // 前置事件

    SyntaxTree.Node tryNode = node("TRY", 10);
    SyntaxTree.Node tryBody = node("BLOCK", 11);
    tryBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#a.", "IdentifierConstant", 12)));
    tryNode.children.add(tryBody);
    SyntaxTree.Node catchNode = node("CATCH", 13);
    catchNode.children.add(node("IDENTIFIER", 14, ref("pkg/A#e.", "IdentifierConstant", 14))); // 异常参数
    SyntaxTree.Node catchBody = node("BLOCK", 15);
    catchBody.children.add(node("IDENTIFIER", 16, ref("pkg/A#b.", "IdentifierConstant", 16)));
    catchNode.children.add(catchBody);
    tryNode.children.add(catchNode);
    mBody.children.add(tryNode);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "a.", "e.", "b."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> conds = nodesOf(sink, GraphModel.LABEL_CONDITION);
    String tryId = null;
    boolean hasCatchCond = false;
    for (Map<String, Object> n : conds) {
      if ("TRY".equals(n.get("kind"))) tryId = (String) n.get("_id");
      if ("CATCH".equals(n.get("kind"))) hasCatchCond = true;
    }
    final String fTry = tryId;
    assertTrue(fTry != null, "TRY condition node exists");
    assertTrue(!hasCatchCond, "no CATCH condition node (removed; catch by edge prop)");
    assertTrue(
        edgesOf(sink, GraphModel.REL_ELSE).isEmpty(),
        "no ELSE edges (try/catch by NEXT)");
    assertTrue(
        edgesOf(sink, GraphModel.REL_SUB).isEmpty(),
        "no SUB edges");
    // TRY 进入 NEXT 时序链：前置事件 e0 → TRY → try 体首事件 a；且 TRY → catch 体首事件 b（异常路径经 NEXT 扇出）。
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next = (from, to) ->
        nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    String e0 = "test::Foo.java#9:0:FIELD";
    String a = "test::Foo.java#12:0:FIELD";
    String b = "test::Foo.java#16:0:FIELD";
    assertTrue(next.test(e0, fTry), "preceding event e0 -> TRY (TRY in order chain)");
    assertTrue(next.test(fTry, a), "TRY -> try body first event a");
    assertTrue(next.test(fTry, b), "TRY -> catch body first event b (exception path via NEXT)");
  }

  @Test
  void tryCatchFinallyForkAtTryBodyEnd() {
    // void m(){ e0; try { a; } catch(e){ b; } finally { f; } next; }
    // TRY 入链;try 体末分叉恒 2——正常 → finally(公共汇合)、异常 → CATCH;
    // catch 链 else-if 式、catch 体尾 → finally;finally → finally 体 → next。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    mBody.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));

    SyntaxTree.Node tryNode = node("TRY", 10);
    SyntaxTree.Node tryBody = node("BLOCK", 11);
    tryBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#a.", "IdentifierConstant", 12)));
    tryNode.children.add(tryBody);
    SyntaxTree.Node catchNode = node("CATCH", 13);
    catchNode.children.add(node("IDENTIFIER", 14, ref("pkg/A#e.", "IdentifierConstant", 14)));
    SyntaxTree.Node catchBody = node("BLOCK", 15);
    catchBody.children.add(node("IDENTIFIER", 16, ref("pkg/A#b.", "IdentifierConstant", 16)));
    catchNode.children.add(catchBody);
    tryNode.children.add(catchNode);
    SyntaxTree.Node finNode = node("FINALLY", 17);
    SyntaxTree.Node finBody = node("BLOCK", 18);
    finBody.children.add(node("IDENTIFIER", 19, ref("pkg/A#f.", "IdentifierConstant", 19)));
    finNode.children.add(finBody);
    tryNode.children.add(finNode);
    mBody.children.add(tryNode);
    mBody.children.add(node("IDENTIFIER", 20, ref("pkg/A#next.", "IdentifierConstant", 20)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "a.", "e.", "b.", "f.", "next."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    String tryId = "test::Foo.java#10:0";
    String finId = "test::Foo.java#17:0";
    String a = "test::Foo.java#12:0:FIELD";
    String b = "test::Foo.java#16:0:FIELD";
    String f = "test::Foo.java#19:0:FIELD";
    String nextNode = "test::Foo.java#20:0:FIELD";
    // 入口:前置 → TRY → try 体首。
    assertTrue(next.test("test::Foo.java#9:0:FIELD", tryId), "e0 -> TRY");
    assertTrue(next.test(tryId, a), "TRY -> try body first event");
    // try 体末正常 → finally 汇合；异常路径：TRY 直接经 NEXT 进入 catch 体首(不物化 CATCH 节点)。
    assertTrue(next.test(a, finId), "try body end (normal) -> finally merge");
    assertTrue(next.test(tryId, b), "TRY -> catch body first event (exception path via NEXT)");
    // catch 体尾、finally 体进入 & 汇合。
    assertTrue(next.test(b, finId), "catch body end -> finally merge");
    assertTrue(next.test(finId, f), "FINALLY -> finally body first event");
    // finally 体末 → 下一事件;next 只由 finally 汇入。
    assertTrue(next.test(f, nextNode), "finally body end -> next");
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

  @Test
  void catchParameterRegistersNameWithoutDisturbingChain() {
    // Kotlin: `try { a() } catch (e: IOException) { b() }`，异常参数 e 是 per-file 的 `local 12`
    // （IdentifierParameter）。handleTryCatch 只 walk catch 体块、不 walk catch 头，故此前该符号
    // 没有声明节点、名字也无人登记，其读节点只能退回裸数字 `12`。
    // 修法：在 catch 头单独登记声明（只建节点、不入链），从而读节点拿到源码名，且不破坏 TRY 时序。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    mBody.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));

    SyntaxTree.Node tryNode = node("TRY", 10);
    SyntaxTree.Node tryBody = node("BLOCK", 11);
    tryBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#a.", "IdentifierConstant", 12)));
    tryNode.children.add(tryBody);
    SyntaxTree.Node catchNode = node("CATCH", 13);
    // catch 头的异常参数：定义 occurrence，局部符号 local 12。
    catchNode.children.add(node("IDENTIFIER", 13, def("local 12", "IdentifierParameter", 13)));
    SyntaxTree.Node catchBody = node("BLOCK", 15);
    // catch 体里对 e 的一次读。
    catchBody.children.add(node("IDENTIFIER", 16, ref("local 12", "IdentifierParameter", 16)));
    catchNode.children.add(catchBody);
    tryNode.children.add(catchNode);
    mBody.children.add(tryNode);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "a."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }
    symbols.put("Foo.kt local 12", info(SymbolInformation.Kind.Parameter, "e"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    // 1) 读节点用源码名 e，而不是裸数字 12。
    List<Map<String, Object>> reads =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 12".equals(v.get("symbol")) && "read".equals(v.get("access")))
            .toList();
    assertEquals(1, reads.size(), "one read of the catch parameter");
    assertEquals("e", reads.get(0).get("name"), "catch param read uses source name, not bare number");

    // 2) TRY 时序不被破坏：e0 → TRY → try 体首 a，且 TRY → catch 体首（异常路径）。
    List<Map<String, Object>> conds = nodesOf(sink, GraphModel.LABEL_CONDITION);
    String tryId = null;
    for (Map<String, Object> n : conds) {
      if ("TRY".equals(n.get("kind"))) tryId = (String) n.get("_id");
    }
    assertTrue(tryId != null, "TRY condition node exists");
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    java.util.function.BiPredicate<String, String> next =
        (from, to) -> nexts.stream().anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
    String e0 = "kotest::Foo.kt#9:0:FIELD";
    String a = "kotest::Foo.kt#12:0:FIELD";
    assertTrue(next.test(e0, tryId), "preceding event still flows into TRY (chain not disturbed)");
    assertTrue(next.test(tryId, a), "TRY still flows into try body first event");

    // 3) 参数绑定不当作赋值：声明节点是 kind=PARAM 且不入 NEXT 链。
    //    若按变量声明处理（IdentifierLocal 分支的延迟写），try 体尾会多一条 NEXT 指向它，
    //    等于在图上说「try 体执行完 → 写下 e」——而 e 由异常本身写入、不属于 try 体执行序。
    List<Map<String, Object>> decls =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 12".equals(v.get("symbol")) && v.get("access") == null)
            .toList();
    assertEquals(1, decls.size(), "catch param declaration node exists");
    assertEquals(GraphModel.VALUE_KIND_PARAM, decls.get(0).get("kind"), "catch param is kind=PARAM");
    String declId = (String) decls.get(0).get("_id");
    assertTrue(
        nexts.stream().noneMatch(e -> declId.equals(e.get("_from")) || declId.equals(e.get("_to"))),
        "catch param declaration is not on the NEXT chain (no spurious write edge from try body)");
  }

  @Test
  void localReadFallsBackToIndexDisplayNameWhenNoDeclarationWalked() {
    // 有些局部符号没有"被 walk 到的声明"（如只出现在未展开表达式里的中间变量），
    // localNamesByFile 因此没有它；此时应回退到索引里的 display_name，而不是裸数字。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/Foo#", "IdentifierType", 1));
    SyntaxTree.Node fun = node("FUN", 2, def("pkg/Foo#bar().", "IdentifierFunctionDefinition", 2));
    // 只有一次读，没有任何声明（模拟声明未被 walk 到）。
    fun.children.add(node("IDENTIFIER", 5, ref("local 15", "IdentifierLocal", 5)));
    cls.children.add(fun);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/Foo#", info(SymbolInformation.Kind.Class, "Foo"));
    symbols.put("pkg/Foo#bar().", info(SymbolInformation.Kind.Method, "bar"));
    symbols.put("Foo.kt" + String.valueOf((char) 0) + "local 15", info(SymbolInformation.Kind.Variable, "callsToExecute"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> reads =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 15".equals(v.get("symbol")) && "read".equals(v.get("access")))
            .toList();
    assertEquals(1, reads.size(), "one read of local 15");
    assertEquals(
        "callsToExecute",
        reads.get(0).get("name"),
        "local read falls back to index display_name, not bare number");
  }

  @Test
  void catchParameterWithLocalSyntaxKindIsNotChainedAsWrite() {
    // 真实语料里 catch 参数常落成 IdentifierLocal（javac 的 EXCEPTION_PARAMETER、Kotlin catch 头）。
    // 该分支会把定义当成「声明式赋值」登记进 pendingLocalWrites 并入 NEXT 链，于是 try 体尾多出
    // 一条 NEXT 指向 catch 参数——把「参数绑定」误作「写」。catch 头登记时须按参数语义处理。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    mBody.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));

    SyntaxTree.Node tryNode = node("TRY", 10);
    SyntaxTree.Node tryBody = node("BLOCK", 11);
    tryBody.children.add(node("IDENTIFIER", 12, ref("pkg/A#a.", "IdentifierConstant", 12)));
    tryNode.children.add(tryBody);
    SyntaxTree.Node catchNode = node("CATCH", 13);
    // 关键：catch 参数的定义 occurrence 是 IdentifierLocal（而非 IdentifierParameter）。
    catchNode.children.add(node("IDENTIFIER", 13, def("local 12", "IdentifierLocal", 13)));
    SyntaxTree.Node catchBody = node("BLOCK", 15);
    catchBody.children.add(node("IDENTIFIER", 16, ref("local 12", "IdentifierParameter", 16)));
    catchNode.children.add(catchBody);
    tryNode.children.add(catchNode);
    mBody.children.add(tryNode);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#e0.", info(SymbolInformation.Kind.Field, "e0."));
    symbols.put("pkg/A#a.", info(SymbolInformation.Kind.Field, "a."));
    symbols.put("Foo.kt" + String.valueOf((char) 0) + "local 12",
        info(SymbolInformation.Kind.Parameter, "e"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    // 声明节点按参数建：kind=PARAM、无 access（不是写）。
    List<Map<String, Object>> decls =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 12".equals(v.get("symbol")) && v.get("access") == null)
            .toList();
    assertEquals(1, decls.size(), "catch param declaration node exists");
    assertEquals(GraphModel.VALUE_KIND_PARAM, decls.get(0).get("kind"), "catch param is kind=PARAM");
    String declId = (String) decls.get(0).get("_id");
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    assertTrue(
        nexts.stream().noneMatch(e -> declId.equals(e.get("_from")) || declId.equals(e.get("_to"))),
        "catch param is not chained as a write (no spurious try-tail -> param edge)");
    // 读节点仍取到源码名。
    List<Map<String, Object>> reads =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 12".equals(v.get("symbol")) && "read".equals(v.get("access")))
            .toList();
    assertEquals("e", reads.get(0).get("name"), "catch param read uses source name");
  }

  @Test
  void localVariableDeclarationStillChainsAsWrite() {
    // 回归护栏：真正的声明式赋值（val x = …）仍须作为「写」入 NEXT 链，未被 catch 参数的修法误伤。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    mBody.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));
    // val x = … 的 LHS 定义 occurrence（IdentifierLocal，且不在 catch 头）。
    mBody.children.add(node("IDENTIFIER", 10, def("local 3", "IdentifierLocal", 10)));
    mBody.children.add(node("IDENTIFIER", 20, ref("pkg/A#tail.", "IdentifierConstant", 20)));
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    symbols.put("pkg/A#e0.", info(SymbolInformation.Kind.Field, "e0."));
    symbols.put("pkg/A#tail.", info(SymbolInformation.Kind.Field, "tail."));
    symbols.put("Foo.kt" + String.valueOf((char) 0) + "local 3",
        info(SymbolInformation.Kind.Variable, "x"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    String declId = "kotest::Foo.kt::local 3";
    List<Map<String, Object>> nexts = edgesOf(sink, GraphModel.REL_NEXT);
    assertTrue(
        nexts.stream().anyMatch(e -> declId.equals(e.get("_from")) || declId.equals(e.get("_to"))),
        "val x = ... declaration is still on the NEXT chain (guard against over-fixing)");
  }

  /** 造 `void m() { <mBody> }` 并返回 sink（mBody 由调用方填充）。 */
  private static MemorySink runBody(java.util.function.Consumer<SyntaxTree.Node> buildMbody) {
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/A#", "IdentifierType", 1));
    SyntaxTree.Node m = node("METHOD", 2, def("pkg/A#m().", "IdentifierFunctionDefinition", 2));
    SyntaxTree.Node mBody = node("BLOCK", 3);
    buildMbody.accept(mBody);
    m.children.add(mBody);
    cls.children.add(m);
    cu.children.add(cls);
    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/A#", info(SymbolInformation.Kind.Class, "A"));
    symbols.put("pkg/A#m().", info(SymbolInformation.Kind.Method, "m"));
    for (String f : new String[] {"e0.", "x.", "E.", "z.", "y."}) {
      symbols.put("pkg/A#" + f, info(SymbolInformation.Kind.Field, f));
    }
    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "test", symbols);
    extractor.extractFile("Foo.java", cu);
    extractor.emitRelationships();
    return sink;
  }

  private static boolean hasNext(MemorySink sink, String from, String to) {
    return edgesOf(sink, GraphModel.REL_NEXT).stream()
        .anyMatch(e -> from.equals(e.get("_from")) && to.equals(e.get("_to")));
  }

  @Test
  void throwTerminatesBranchAndGetsSlotNode() {
    // void m() { e0; if (x) { throw new E(); } z; }
    // throw 是非正常出口：应与 return 对称——有 THROW 槽节点，且其后同分支代码不可达。
    MemorySink sink =
        runBody(
            mb -> {
              mb.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));
              SyntaxTree.Node iff = node("IF", 10);
              iff.children.add(node("IDENTIFIER", 10, ref("pkg/A#x.", "IdentifierConstant", 10)));
              SyntaxTree.Node then = node("BLOCK", 11);
              SyntaxTree.Node thr = node("THROW", 12);
              thr.children.add(node("IDENTIFIER", 12, ref("pkg/A#E.", "IdentifierConstant", 12)));
              then.children.add(thr);
              iff.children.add(then);
              mb.children.add(iff);
              mb.children.add(node("IDENTIFIER", 20, ref("pkg/A#z.", "IdentifierConstant", 20)));
            });

    // 1) THROW 槽节点存在，且抛出表达式读取在其之前入链（…→E 读→THROW）。
    List<Map<String, Object>> throwsNodes =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> GraphModel.VALUE_KIND_THROW.equals(v.get("kind")))
            .toList();
    assertEquals(1, throwsNodes.size(), "throw slot node exists");
    String throwId = (String) throwsNodes.get(0).get("_id");
    String eRead = "test::Foo.java#12:0:FIELD";
    assertTrue(hasNext(sink, eRead, throwId), "thrown expression read precedes the THROW slot");

    // 2) throw 分支终止：其槽不再续接 switch/if 之后的不可达代码。
    String z = "test::Foo.java#20:0:FIELD";
    assertTrue(!hasNext(sink, throwId, z), "throw does not fall through to code after the branch");

    // 3) 假路径仍可达（条件不真时正常落到 z）。
    String cond = "test::Foo.java#10:0";
    assertTrue(hasNext(sink, cond, z), "false path still reaches code after the branch");
  }

  @Test
  void returnTerminatesBranchChain() {
    // void m() { e0; return x; z; }  —— 同块内 return 之后的代码不可达。
    MemorySink sink =
        runBody(
            mb -> {
              mb.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));
              SyntaxTree.Node ret = node("RETURN", 12);
              ret.children.add(node("IDENTIFIER", 12, ref("pkg/A#E.", "IdentifierConstant", 12)));
              mb.children.add(ret);
              mb.children.add(node("IDENTIFIER", 20, ref("pkg/A#z.", "IdentifierConstant", 20)));
            });

    String retId = "test::Foo.java#12:0:RETURN";
    String z = "test::Foo.java#20:0:FIELD";
    assertTrue(hasNext(sink, "test::Foo.java#12:0:FIELD", retId), "returned value read precedes RETURN");
    assertTrue(!hasNext(sink, retId, z), "return does not chain to unreachable code in the same block");
  }

  @Test
  void throwInOneBranchDoesNotBlockOtherBranchFallThrough() {
    // void m() { if (c) { x; } else { throw new E(); } y; }
    // else 分支终止，但其终止不得影响 then 分支正常落到 y。
    MemorySink sink =
        runBody(
            mb -> {
              SyntaxTree.Node iff = node("IF", 10);
              iff.children.add(node("IDENTIFIER", 10, ref("pkg/A#x.", "IdentifierConstant", 10)));
              SyntaxTree.Node then = node("BLOCK", 11);
              then.children.add(node("IDENTIFIER", 11, ref("pkg/A#x.", "IdentifierConstant", 11)));
              SyntaxTree.Node els = node("BLOCK", 13);
              SyntaxTree.Node thr = node("THROW", 14);
              thr.children.add(node("IDENTIFIER", 14, ref("pkg/A#E.", "IdentifierConstant", 14)));
              els.children.add(thr);
              iff.children.add(then);
              iff.children.add(els);
              mb.children.add(iff);
              mb.children.add(node("IDENTIFIER", 20, ref("pkg/A#y.", "IdentifierConstant", 20)));
            });

    String xRead = "test::Foo.java#11:0:FIELD";
    String y = "test::Foo.java#20:0:FIELD";
    String throwId = "test::Foo.java#14:0:THROW";
    assertTrue(hasNext(sink, xRead, y), "non-throwing branch still falls through to code after the if");
    assertTrue(!hasNext(sink, throwId, y), "throwing branch does not fall through");
  }

  @Test
  void conditionalAbruptDoesNotTerminateEnclosingBlock() {
    // void m() { e0; if (c) return; z; }
    // `if (c) return;`（无大括号）的 return 是外层块的子节点，但只在分支作用域里执行：
    // 条件为假时仍会落到 z。故它不得终止外层块——否则 `cond --false--> z` 这条路径会丢。
    MemorySink sink =
        runBody(
            mb -> {
              mb.children.add(node("IDENTIFIER", 9, ref("pkg/A#e0.", "IdentifierConstant", 9)));
              SyntaxTree.Node iff = node("IF", 10);
              iff.children.add(node("IDENTIFIER", 10, ref("pkg/A#x.", "IdentifierConstant", 10)));
              SyntaxTree.Node ret = node("RETURN", 11);
              ret.children.add(node("IDENTIFIER", 11, ref("pkg/A#E.", "IdentifierConstant", 11)));
              iff.children.add(ret); // then 分支是裸语句（无 BLOCK），与外层同块
              mb.children.add(iff);
              mb.children.add(node("IDENTIFIER", 20, ref("pkg/A#z.", "IdentifierConstant", 20)));
            });

    String cond = "test::Foo.java#10:0";
    String z = "test::Foo.java#20:0:FIELD";
    assertTrue(hasNext(sink, cond, z), "conditional return leaves the false path to later code");
  }

  @Test
  void localParameterReadsUseSourceNameNotBareNumber() {
    // Kotlin 的 lambda 形参 / catch 参数都是 IdentifierParameter，但符号是 per-file 的 `local N`。
    // 声明处必须把源码名登记进 localNamesByFile，否则同符号的读节点只能退回裸数字 `N`。
    // 这里模拟 `fun bar() { list.forEach { sink -> sink.flush() } }`：
    // local 67 的声明（sink）与读（sink.flush() 里的 sink）都要显示为 sink。
    SyntaxTree.Node cu = node("COMPILATION_UNIT", 0);
    SyntaxTree.Node cls = node("CLASS", 1, def("pkg/Foo#", "IdentifierType", 1));
    SyntaxTree.Node fun = node("FUN", 2, def("pkg/Foo#bar().", "IdentifierFunctionDefinition", 2));
    // lambda 形参声明：local 67，源码名 sink。
    fun.children.add(node("IDENTIFIER", 3, def("local 67", "IdentifierParameter", 3)));
    // 对 local 67 的一次读（如 sink.flush() 的接收者）。
    fun.children.add(node("IDENTIFIER", 4, ref("local 67", "IdentifierParameter", 4)));
    cls.children.add(fun);
    cu.children.add(cls);

    Map<String, SymbolInformation> symbols = new LinkedHashMap<>();
    symbols.put("pkg/Foo#", info(SymbolInformation.Kind.Class, "Foo"));
    symbols.put("pkg/Foo#bar().", info(SymbolInformation.Kind.Method, "bar"));
    // 局部符号按 (文件, 符号) 复合键查找，与 ScipAggregator 的收集口径一致。
    symbols.put("Foo.kt local 67", info(SymbolInformation.Kind.Parameter, "sink"));

    MemorySink sink = new MemorySink();
    GraphExtractor extractor = new GraphExtractor(sink, "kotest", symbols);
    extractor.extractFile("Foo.kt", cu);
    extractor.emitRelationships();

    List<Map<String, Object>> reads =
        nodesOf(sink, GraphModel.LABEL_VALUE).stream()
            .filter(v -> "local 67".equals(v.get("symbol")) && "read".equals(v.get("access")))
            .toList();
    assertEquals(1, reads.size(), "one read of local 67");
    assertEquals("sink", reads.get(0).get("name"), "local param read uses source name, not bare number");
  }
}

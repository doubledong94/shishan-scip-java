package org.scip_code.scip_java.aggregator.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.ScipRange;
import org.scip_code.scip_java.shared.ScipSymbols;
import org.scip_code.scip_java.shared.SyntaxTree;

/**
 * Walks the merged per-file syntax trees and rewritten {@link SymbolInformation} and streams the
 * code graph into a {@link GraphSink}.
 *
 * <p>Handles both compiler front-ends:
 *
 * <ul>
 *   <li>javac: definitions sit on the structural node itself ({@code CLASS}/{@code METHOD}/{@code
 *       VARIABLE});
 *   <li>scip-kotlinc: the tree is the Kotlin PSI lighter-AST; definitions sit on the name {@code
 *       IDENTIFIER} under the structural node ({@code CLASS}/{@code FUN}/...), and call/assignment
 *       nodes are named {@code CALL_EXPRESSION} / {@code BINARY_EXPRESSION} (with an {@code
 *       OPERATION_REFERENCE} carrying the operator).
 * </ul>
 *
 * <p>Two kinds of nodes exist per the graph model ({@code shishanMcp/doc/GRAPH_MODEL.md}):
 *
 * <ul>
 *   <li><b>declaration nodes</b> ({@code Class}/{@code Method}/{@code Field}/{@code Value(PARAM/…)})
 *       — one per defined symbol;
 *   <li><b>runtime nodes</b> ({@code Value} reads/writes, {@code CalledMethod}/{@code
 *       CalledParam}/{@code CalledReturn}) — one per occurrence, mirroring the old viewer's
 *       {@code runtimeKey = key(structure;sentence;index)} model.
 * </ul>
 *
 * <p>Edges: declarations wired by {@code DECLARES}/{@code HAS_PARAM} (SCIP symbol hierarchy) and
 * {@code EXTENDS}/{@code OVERRIDES}; runtime layer wired by {@code FLOWS} (assignment, last-write,
 * argument passing, return), {@code CONTROLS} (value guards a branch), {@code REF} (receiver
 * reaches a call), {@code ROOT}/{@code SUB}/{@code ELSE}/{@code LEADS_TO} (branches) and {@code
 * CALLS}/{@code ARG_OF}/{@code SCOPED_BY} (calls).
 */
public final class GraphExtractor {

  private final GraphSink writer;
  private final String project;
  private final Map<String, SymbolInformation> symbols;

  // Post-pass bookkeeping: created declaration symbols → node label.
  private final Map<String, String> createdSymbolLabel = new LinkedHashMap<>();

  private final Deque<String> methodRootConds = new ArrayDeque<>();
  private final Deque<String> conds = new ArrayDeque<>();
  private final Deque<String> methodSymbols = new ArrayDeque<>();
  // Cross-method binding: callee method symbol → its params in declaration order.
  private final Map<String, java.util.List<String>> paramsByMethod = new java.util.HashMap<>();
  // Cross-method binding: callee method symbol → its return-slot runtime ids.
  private final Map<String, java.util.List<String>> returnsByMethod = new java.util.HashMap<>();
  // Local variables get per-file SCIP symbols ("local N"); map (file, localSymbol) → source name.
  private final Map<String, Map<String, String>> localNamesByFile = new java.util.HashMap<>();
  // Last-write data-flow scopes: one frame per method / per condition branch. Branch entry copies
  // the parent's last-write state; on condition exit the union of all branches' writes is merged
  // back (may-analysis), mirroring the old viewer's DataFlowVisitor block scoping.
  private final Deque<Scope> scopeStack = new ArrayDeque<>();
  // Runtime ids that are assignment targets (writes).
  private final java.util.Set<String> writeRuntimeIds = new java.util.HashSet<>();

  /** A data-flow scope: symbol → set of possible last-write runtime ids, plus reads with no source. */
  private static final class Scope {
    final Map<String, java.util.Set<String>> lastWrites = new java.util.HashMap<>();
    /** Runtime id → source line of the write (for loop-feedback ordering). */
    final Map<String, Integer> writeLines = new java.util.HashMap<>();
    /** Symbols assigned unconditionally within this scope (definite-assignment tracking). */
    final java.util.Set<String> updated100Percent = new java.util.HashSet<>();
    final List<UnwrittenRead> unwrittenReads = new ArrayList<>();
    boolean hasReturn = false;
  }

  private static final class UnwrittenRead {
    final String symbol;
    final String id;
    final int line;

    UnwrittenRead(String symbol, String id, int line) {
      this.symbol = symbol;
      this.id = id;
      this.line = line;
    }
  }

  private Scope currentScope() {
    return scopeStack.peek();
  }

  public GraphExtractor(
      GraphSink writer, String project, Map<String, SymbolInformation> symbols) {
    this.writer = writer;
    this.project = project;
    this.symbols = symbols;
  }

  // ---------------------------------------------------------------------------
  // Id helpers
  // ---------------------------------------------------------------------------

  private static String declId(String project, String file, String symbol) {
    if (ScipSymbols.isLocal(symbol)) return project + "::" + file + "::" + symbol;
    return project + "::" + symbol;
  }

  private static String runtimeId(String project, String file, ScipRange range, String tag) {
    String base =
        project
            + "::"
            + file
            + "#"
            + (range == null ? "0:0" : range.startLine() + ":" + range.startCharacter());
    return tag == null ? base : base + ":" + tag;
  }

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  public void extractFile(String file, SyntaxTree.Node root) {
    walk(file, root, null, -1);
  }

  /** Emits declaration-relationship edges ({@code DECLARES}/{@code HAS_PARAM}/{@code EXTENDS}/{@code OVERRIDES}). */
  public void emitRelationships() {
    for (Map.Entry<String, String> entry : createdSymbolLabel.entrySet()) {
      String symbol = entry.getKey();
      String label = entry.getValue();
      String owner = ownerOf(symbol);
      if (owner == null || owner.isEmpty()) continue;
      if (!createdSymbolLabel.containsKey(owner)) continue;
      String ownerId = declId(project, "", owner);
      String memberId = declId(project, "", symbol);
      if (GraphModel.LABEL_VALUE.equals(label)) {
        writer.addEdge(
            GraphModel.REL_HAS_PARAM, GraphModel.LABEL_METHOD, ownerId, GraphModel.LABEL_VALUE, memberId);
      } else {
        writer.addEdge(
            GraphModel.REL_DECLARES, GraphModel.LABEL_CLASS, ownerId, label, memberId);
      }
    }

    for (Map.Entry<String, SymbolInformation> entry : symbols.entrySet()) {
      String sourceSymbol = entry.getKey();
      SymbolInformation info = entry.getValue();
      String sourceId = declId(project, "", sourceSymbol);
      for (org.scip_code.scip.Relationship rel : info.getRelationshipsList()) {
        if (!rel.getIsImplementation()) continue;
        String targetSymbol = rel.getSymbol();
        if (targetSymbol.isEmpty() || !symbols.containsKey(targetSymbol)) continue;
        String targetId = declId(project, "", targetSymbol);
        if (sourceSymbol.equals(targetSymbol)) continue;
        if (isTypeSymbol(info.getKind()) && isTypeSymbol(symbols.get(targetSymbol).getKind())) {
          writer.addEdge(
              GraphModel.REL_EXTENDS,
              GraphModel.LABEL_CLASS, sourceId,
              GraphModel.LABEL_CLASS, targetId);
        } else {
          writer.addEdge(
              GraphModel.REL_OVERRIDES,
              GraphModel.LABEL_METHOD, sourceId,
              GraphModel.LABEL_METHOD, targetId);
        }
      }
    }
  }

  private static boolean isTypeSymbol(SymbolInformation.Kind kind) {
    return kind == SymbolInformation.Kind.Class
        || kind == SymbolInformation.Kind.Interface
        || kind == SymbolInformation.Kind.Enum;
  }

  // ---------------------------------------------------------------------------
  // Tree walk
  // ---------------------------------------------------------------------------

  private void walk(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    enter(file, node, parent, index);
    if (isConditionKind(node.kind)) {
      walkConditionChildren(file, node);
    } else {
      List<SyntaxTree.Node> children = node.children;
      for (int i = 0; i < children.size(); i++) {
        walk(file, children.get(i), node, i);
      }
    }
    exit(file, node);
  }

  /**
   * Walks a condition node's children with branch-aware data-flow scoping: the condition expression
   * runs in the parent scope, then each branch runs in a copy of the parent's last-write state
   * (so sibling branches never see each other's writes), and on exit the union of all branches'
   * writes is merged back into the parent. Loop bodies additionally get feedback edges from writes
   * to earlier unwritten reads (next-iteration flow).
   */
  private void walkConditionChildren(String file, SyntaxTree.Node node) {
    List<SyntaxTree.Node> children = node.children;
    List<SyntaxTree.Node> branches = branchChildren(node);

    for (int i = 0; i < children.size(); i++) {
      SyntaxTree.Node child = children.get(i);
      if (branches.contains(child)) continue;
      walk(file, child, node, i);
    }

    List<Scope> branchScopes = new ArrayList<>();
    for (SyntaxTree.Node branch : branches) {
      pushBranchScope();
      walk(file, branch, node, children.indexOf(branch));
      branchScopes.add(scopeStack.pop());
    }
    mergeBranchScopes(branchScopes, node);
  }

  private void pushBranchScope() {
    Scope parent = currentScope();
    Scope copy = new Scope();
    if (parent != null) {
      for (Map.Entry<String, java.util.Set<String>> e : parent.lastWrites.entrySet()) {
        copy.lastWrites.put(e.getKey(), new java.util.HashSet<>(e.getValue()));
      }
    }
    scopeStack.push(copy);
  }

  private void mergeBranchScopes(List<Scope> branches, SyntaxTree.Node node) {
    Scope parent = currentScope();
    if (parent == null) return;

    // Definite assignment: when every branch unconditionally re-writes a variable (no branch
    // returns) and the split is closed (if/else), the pre-branch last-write is dead — drop it so
    // reads after the branch don't see it. Loops are never closed (may not execute).
    boolean closed = node.kind.equals("IF") && branches.size() >= 2;
    if (closed) {
      for (String v : new ArrayList<>(parent.lastWrites.keySet())) {
        boolean everyBranch = true;
        for (Scope branch : branches) {
          if (branch.hasReturn || !branch.updated100Percent.contains(v)) {
            everyBranch = false;
            break;
          }
        }
        if (everyBranch) parent.lastWrites.remove(v);
      }
    }

    // Union merge (return-terminated branches never reach code after the split).
    for (Scope branch : branches) {
      if (branch.hasReturn) continue;
      for (Map.Entry<String, java.util.Set<String>> e : branch.lastWrites.entrySet()) {
        java.util.Set<String> target =
            parent.lastWrites
                .computeIfAbsent(e.getKey(), k -> new java.util.HashSet<>());
        target.addAll(e.getValue());
        capUnionSet(target);
        for (String id : e.getValue()) {
          Integer line = branch.writeLines.get(id);
          if (line != null) parent.writeLines.put(id, line);
        }
      }
      // Unwritten reads propagate up so a loop's feedback can reach reads nested in branches.
      parent.unwrittenReads.addAll(branch.unwrittenReads);
    }

    // Loop feedback: reads with no preceding write in the loop see only the loop's writes that
    // happen LATER (next-iteration flow).
    if (isLoopKind(node.kind)) {
      for (Scope branch : branches) {
        for (UnwrittenRead rw : branch.unwrittenReads) {
          java.util.Set<String> writes = branch.lastWrites.get(rw.symbol);
          if (writes == null) continue;
          for (String w : writes) {
            Integer wLine = branch.writeLines.get(w);
            if (wLine == null || wLine <= rw.line) continue;
            if (!w.equals(rw.id)) {
              writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, w, GraphModel.LABEL_VALUE, rw.id);
            }
          }
        }
      }
    }
  }

  /**
   * Bounds the size of a may-analysis last-write set. Without a cap, deeply nested branches make
   * the union grow without bound and blow up both the fork's memory and the Neo4j edge count.
   */
  private static void capUnionSet(java.util.Set<String> set) {
    if (set.size() <= MAX_UNION_WRITES) return;
    java.util.Set<String> capped = new java.util.LinkedHashSet<>();
    int skip = set.size() - MAX_UNION_WRITES;
    for (String id : set) {
      if (skip-- > 0) continue;
      capped.add(id);
    }
    set.clear();
    set.addAll(capped);
  }

  private static final int MAX_UNION_WRITES = 64;

  private void enter(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    // Method scope: a structural method node (javac METHOD / Kotlin FUN) anchors a root condition
    // so body-level calls can be SCOPED_BY it. Always pushed (even without a resolvable symbol) so
    // exit() is symmetric.
    if (isMethodKind(node.kind)) {
      pushMethodScope(file, node, structuralDefinition(node));
    }

    SyntaxTree.OccurrenceData def = definition(node);
    if (def != null) createDeclaration(file, node, def);

    if (isAssignment(node)) {
      handleAssignment(file, node);
    }
    if (isMemberSelectKind(node.kind)) {
      handleMemberSelect(file, node);
    }
    if (isIndexAccessKind(node.kind)) {
      handleIndexAccess(file, node);
    }
    if (isInvocationKind(node.kind)) {
      enterInvocation(file, node, parent);
    }
    if (isConditionKind(node.kind)) {
      enterCondition(file, node, parent, index);
    }
    if (isReturnKind(node.kind)) {
      handleReturn(file, node);
    }

    emitReferenceValues(file, node);
  }

  private void exit(String file, SyntaxTree.Node node) {
    if (isConditionKind(node.kind) && !conds.isEmpty()) conds.pop();
    if (isMethodKind(node.kind)) {
      if (!methodRootConds.isEmpty()) methodRootConds.pop();
      if (!conds.isEmpty()) conds.pop(); // method root condition
      if (!scopeStack.isEmpty()) scopeStack.pop();
      if (!methodSymbols.isEmpty()) methodSymbols.pop();
    }
    if (node.kind.equals("BLOCK")) {
      emitNextChain(file, node);
    }
  }

  // ---------------------------------------------------------------------------
  // Execution order (the old viewer's CodeOrderVisitor, 5th direction)
  // ---------------------------------------------------------------------------

  private static final class Anchor {
    final String id;
    final String label;

    Anchor(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  /**
   * NEXT chain within a block: connect consecutive statement anchors in source order
   * ({@code stmt1 → stmt2 → …}), mirroring the old viewer's {@code codeOrder(mk, prev, next)}.
   */
  private void emitNextChain(String file, SyntaxTree.Node block) {
    List<Anchor> anchors = new ArrayList<>();
    for (SyntaxTree.Node child : nonWhitespaceChildren(block)) {
      Anchor a = firstRuntimeAnchor(file, child);
      if (a != null) anchors.add(a);
    }
    for (int i = 0; i + 1 < anchors.size(); i++) {
      Anchor a = anchors.get(i);
      Anchor b = anchors.get(i + 1);
      if (!a.id.equals(b.id)) {
        writer.addEdge(GraphModel.REL_NEXT, a.label, a.id, b.label, b.id);
      }
    }
  }

  /** The first runtime anchor inside a statement subtree (value ref, then call, then condition). */
  private Anchor firstRuntimeAnchor(String file, SyntaxTree.Node subtree) {
    SyntaxTree.OccurrenceData v = firstValueReference(subtree);
    if (v != null) {
      String kind = valueKindFor(v.syntaxKind);
      if (kind != null) {
        return new Anchor(runtimeId(project, file, v.range, kind), GraphModel.LABEL_VALUE);
      }
    }
    SyntaxTree.Node inv = firstNodeOfKind(subtree, GraphExtractor::isInvocationKind);
    if (inv != null) {
      return new Anchor(runtimeId(project, file, inv.range, null), GraphModel.LABEL_CALLED_METHOD);
    }
    SyntaxTree.Node cond = firstNodeOfKind(subtree, GraphExtractor::isConditionKind);
    if (cond != null) {
      return new Anchor(runtimeId(project, file, cond.range, null), GraphModel.LABEL_CONDITION);
    }
    return null;
  }

  private static SyntaxTree.Node firstNodeOfKind(
      SyntaxTree.Node node, java.util.function.Predicate<String> kindTest) {
    if (kindTest.test(node.kind)) return node;
    for (SyntaxTree.Node child : node.children) {
      SyntaxTree.Node r = firstNodeOfKind(child, kindTest);
      if (r != null) return r;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Declarations
  // ---------------------------------------------------------------------------

  private void pushMethodScope(String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def) {
    scopeStack.push(new Scope());
    String symbol = def != null ? def.symbol : "";
    boolean hasSymbol = def != null && !symbol.isEmpty() && !ScipSymbols.isLocal(symbol);
    if (hasSymbol) {
      String id = declId(project, file, symbol);
      SymbolInformation info = symbols.get(symbol);
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("name", displayName(info, symbol));
      props.put("file", file);
      props.put("line", rangeLine(def));
      props.put("symbol", symbol);
      boolean isConstructor = info != null && info.getKind() == SymbolInformation.Kind.Constructor;
      props.put("isConstructor", isConstructor);
      if (info != null && info.hasSignatureDocumentation()) {
        props.put("signature", info.getSignatureDocumentation().getText());
      }
      writer.addNode(GraphModel.LABEL_METHOD, id, props);
      methodSymbols.push(symbol);
    } else {
      methodSymbols.push("");
    }
    // The root condition anchors SCOPED_BY for body-level runtime nodes; created in all cases so
    // the enter/exit stacks stay symmetric.
    ScipRange range = def != null && def.range != null ? def.range : node.range;
    String rootCond = runtimeId(project, file, range, "root");
    Map<String, Object> rootProps = new LinkedHashMap<>();
    rootProps.put("file", file);
    rootProps.put("line", range == null ? 0 : range.startLine());
    rootProps.put("kind", GraphModel.CONDITION_KIND_METHOD);
    writer.addNode(GraphModel.LABEL_CONDITION, rootCond, rootProps);
    if (hasSymbol) {
      writer.addEdge(
          GraphModel.REL_ROOT,
          GraphModel.LABEL_METHOD, declId(project, file, symbol),
          GraphModel.LABEL_CONDITION, rootCond);
    }
    methodRootConds.push(rootCond);
    conds.push(rootCond);
  }

  private void createDeclaration(String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def) {
    String symbol = def.symbol;
    if (symbol.isEmpty()) return;
    String syntaxKind = def.syntaxKind;
    SymbolInformation info = symbols.get(symbol);
    String name = displayName(info, symbol);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", name);
    props.put("file", file);
    props.put("line", rangeLine(def));
    props.put("symbol", symbol);
    if (info != null && info.hasSignatureDocumentation()) {
      props.put("signature", info.getSignatureDocumentation().getText());
    }

    String label;
    if ("IdentifierType".equals(syntaxKind)) {
      props.put("kind", typeKind(info));
      props.put("package", packageOf(symbol));
      label = GraphModel.LABEL_CLASS;
    } else if ("IdentifierFunctionDefinition".equals(syntaxKind)) {
      boolean isConstructor =
          info != null && info.getKind() == SymbolInformation.Kind.Constructor;
      props.put("isConstructor", isConstructor);
      label = GraphModel.LABEL_METHOD;
    } else if ("IdentifierParameter".equals(syntaxKind)) {
      props.put("kind", GraphModel.VALUE_KIND_PARAM);
      label = GraphModel.LABEL_VALUE;
      if (!ScipSymbols.isLocal(symbol)) {
        // Record the param against its method (declaration walk order == param order).
        String owner = ownerOf(symbol);
        if (owner != null && !owner.isEmpty()) {
          paramsByMethod.computeIfAbsent(owner, k -> new ArrayList<>()).add(symbol);
        }
      }
    } else if ("IdentifierLocal".equals(syntaxKind)) {
      props.put("kind", GraphModel.VALUE_KIND_LOCAL_VAR);
      label = GraphModel.LABEL_VALUE;
      if (ScipSymbols.isLocal(symbol)) {
        // Locals use per-file "local N" symbols; remember the real source name for naming reads.
        localNamesByFile
            .computeIfAbsent(file, k -> new java.util.HashMap<>())
            .put(symbol, name);
      }
    } else {
      // javac IdentifierConstant, Kotlin Identifier for non-local properties → field.
      props.put("kind", "field");
      label = GraphModel.LABEL_FIELD;
    }
    String id = declId(project, file, symbol);
    writer.addNode(label, id, props);
    if (!ScipSymbols.isLocal(symbol)) {
      createdSymbolLabel.putIfAbsent(symbol, label);
    }
  }

  // ---------------------------------------------------------------------------
  // Runtime value nodes (reads / writes), mirroring the old viewer's runtime items
  // ---------------------------------------------------------------------------

  private void emitReferenceValues(String file, SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role != 0) continue;
      String kind = valueKindFor(occ.syntaxKind);
      if (kind == null) continue;
      String id = runtimeId(project, file, occ.range, kind);
      boolean isWrite = writeRuntimeIds.contains(id);
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("name", valueName(file, occ.symbol));
      props.put("symbol", occ.symbol);
      props.put("file", file);
      props.put("line", rangeLine(occ));
      props.put("kind", kind);
      props.put("access", isWrite ? "write" : "read");
      writer.addNode(GraphModel.LABEL_VALUE, id, props);

      String scope = innermostCond();
      if (scope != null) {
        writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, id, GraphModel.LABEL_CONDITION, scope);
      }

      if (isWrite) {
        Scope sc = currentScope();
        if (sc != null) {
          sc.lastWrites.put(occ.symbol, new java.util.HashSet<>(java.util.List.of(id)));
          sc.writeLines.put(id, rangeLine(occ));
          sc.updated100Percent.add(occ.symbol);
        }
      } else {
        Scope sc = currentScope();
        if (sc != null) {
          java.util.Set<String> sources = sc.lastWrites.get(occ.symbol);
          if (sources != null && !sources.isEmpty()) {
            boolean anyLocal = false;
            for (String src : sources) {
              // A write is "local" to this scope subtree if it was written here (direct or via a
              // nested merge); writes copied from an outer scope on branch entry are not.
              if (sc.writeLines.containsKey(src)) anyLocal = true;
              if (!src.equals(id)) {
                writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, src, GraphModel.LABEL_VALUE, id);
              }
            }
            if (!anyLocal) {
              // Only outer-scope sources: still a loop-feedback candidate (loop-carried
              // dependency), mirroring the old viewer's writtenAllFromOuterScope.
              sc.unwrittenReads.add(new UnwrittenRead(occ.symbol, id, rangeLine(occ)));
            }
          } else {
            sc.unwrittenReads.add(new UnwrittenRead(occ.symbol, id, rangeLine(occ)));
          }
        }
      }
    }
  }

  /** Kind of a runtime value reference (read/write of a field/param/localvar), or null. */
  private static String valueKindFor(String syntaxKind) {
    if (syntaxKind == null) return null;
    return switch (syntaxKind) {
      case "IdentifierConstant" -> GraphModel.VALUE_KIND_FIELD;
      case "Identifier" -> GraphModel.VALUE_KIND_FIELD; // Kotlin non-local property
      case "IdentifierParameter" -> GraphModel.VALUE_KIND_PARAM;
      case "IdentifierLocal" -> GraphModel.VALUE_KIND_LOCAL_VAR;
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------
  // Assignments → FLOWS
  // ---------------------------------------------------------------------------

  private void handleAssignment(String file, SyntaxTree.Node node) {
    List<SyntaxTree.Node> operands = assignmentOperands(node);
    if (operands.size() < 2) return;
    SyntaxTree.Node lhs = operands.get(0);
    SyntaxTree.Node rhs = operands.get(operands.size() - 1);

    String writeId;
    if (isMemberSelectKind(lhs.kind)) {
      // `obj.field = x`: the write target is the MEMBER field (not the base object). The base is
      // recorded as written too (reversedRef: the object is mutated), mirroring the old viewer's
      // visitSentence handling.
      SyntaxTree.OccurrenceData memberOcc = memberValueReference(lhs);
      if (memberOcc == null) return;
      String kind = valueKindFor(memberOcc.syntaxKind);
      if (kind == null) return;
      writeId = runtimeId(project, file, memberOcc.range, kind);
      markBaseWritten(file, lhs);
    } else {
      String id = firstValueRuntimeId(file, lhs);
      if (id == null) return;
      writeId = id;
    }

    writeRuntimeIds.add(writeId);
    String rhsId = firstValueRuntimeId(file, rhs);
    if (rhsId != null && !rhsId.equals(writeId)) {
      writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, rhsId, GraphModel.LABEL_VALUE, writeId);
    }
  }

  /**
   * reversedRef: writing {@code obj.field} mutates {@code obj}, so the base's variable is recorded
   * as written — subsequent reads of {@code obj} (or its other fields) see this access as their
   * last write.
   */
  private void markBaseWritten(String file, SyntaxTree.Node memberSelect) {
    SyntaxTree.Node base = rootBase(memberSelect);
    if (base == null) return;
    SyntaxTree.OccurrenceData occ = firstValueReference(base);
    if (occ == null) return;
    String kind = valueKindFor(occ.syntaxKind);
    if (kind == null) return;
    Scope sc = currentScope();
    if (sc == null) return;
    String id = runtimeId(project, file, occ.range, kind);
    sc.lastWrites.put(occ.symbol, new java.util.HashSet<>(java.util.List.of(id)));
    sc.writeLines.put(id, rangeLine(occ));
  }

  /** Nesting direction: the base object REF the field/member value it accesses. */
  private void handleMemberSelect(String file, SyntaxTree.Node node) {
    SyntaxTree.Node base = rootBase(node);
    SyntaxTree.OccurrenceData member = memberValueReference(node);
    if (base == null || member == null) return;
    String kind = valueKindFor(member.syntaxKind);
    if (kind == null) return;
    String memberId = runtimeId(project, file, member.range, kind);
    List<String> baseIds = new ArrayList<>();
    collectValueIds(file, base, baseIds);
    // reversedRef: a member WRITE (assignment target) flows member → base instead of base → member,
    // mirroring the old viewer's markUnreadReturn / setReversedRefRecur.
    boolean memberIsWrite = writeRuntimeIds.contains(memberId);
    for (String b : baseIds) {
      if (b.equals(memberId)) continue;
      if (memberIsWrite) {
        writer.addEdge(GraphModel.REL_REF, GraphModel.LABEL_VALUE, memberId, GraphModel.LABEL_VALUE, b);
      } else {
        writer.addEdge(GraphModel.REL_REF, GraphModel.LABEL_VALUE, b, GraphModel.LABEL_VALUE, memberId);
      }
    }
  }

  /** The root object of a member-access chain ({@code a.b.c} → {@code a}). */
  private static SyntaxTree.Node rootBase(SyntaxTree.Node memberSelect) {
    SyntaxTree.Node cur = memberSelect;
    while (isMemberSelectKind(cur.kind)) {
      SyntaxTree.Node base = firstOperand(cur);
      if (base == null) return null;
      if (!isMemberSelectKind(base.kind)) return base;
      cur = base;
    }
    return cur;
  }

  /** The member (field/method name) operand of a member access — its last non-separator child. */
  private static SyntaxTree.Node memberOperand(SyntaxTree.Node memberSelect) {
    List<SyntaxTree.Node> kids = nonWhitespaceChildren(memberSelect);
    for (int i = kids.size() - 1; i >= 0; i--) {
      SyntaxTree.Node k = kids.get(i);
      if (!k.kind.equals("DOT") && !k.kind.equals("SAFE_ACCESS")) return k;
    }
    return null;
  }

  private static SyntaxTree.OccurrenceData memberValueReference(SyntaxTree.Node memberSelect) {
    SyntaxTree.Node member = memberOperand(memberSelect);
    if (member == null) return null;
    return firstValueReference(member);
  }

  /** First non-whitespace, non-separator child (the receiver operand of a member access). */
  private static SyntaxTree.Node firstOperand(SyntaxTree.Node node) {
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("WHITE_SPACE")
          || child.kind.equals("DOT")
          || child.kind.equals("SAFE_ACCESS")) {
        continue;
      }
      return child;
    }
    return null;
  }

  private static List<SyntaxTree.Node> assignmentOperands(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("WHITE_SPACE") || child.kind.equals("OPERATION_REFERENCE")) continue;
      out.add(child);
    }
    return out;
  }

  private String firstValueRuntimeId(String file, SyntaxTree.Node subtree) {
    SyntaxTree.OccurrenceData occ = firstValueReference(subtree);
    if (occ == null) return null;
    String kind = valueKindFor(occ.syntaxKind);
    if (kind == null) return null;
    return runtimeId(project, file, occ.range, kind);
  }

  private static SyntaxTree.OccurrenceData firstValueReference(SyntaxTree.Node subtree) {
    for (SyntaxTree.OccurrenceData occ : subtree.occurrences) {
      if (occ.role == 0 && valueKindFor(occ.syntaxKind) != null) return occ;
    }
    for (SyntaxTree.Node child : subtree.children) {
      SyntaxTree.OccurrenceData d = firstValueReference(child);
      if (d != null) return d;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Returns → FLOWS
  // ---------------------------------------------------------------------------

  private void handleReturn(String file, SyntaxTree.Node node) {
    // A return terminates the current branch regardless of whether it carries a value.
    Scope cur = currentScope();
    if (cur != null) cur.hasReturn = true;
    // The returned value flows into the enclosing method's return slot.
    List<SyntaxTree.Node> operands = nonWhitespaceChildren(node);
    if (operands.isEmpty()) return;
    String valueId = firstValueRuntimeId(file, operands.get(operands.size() - 1));
    if (valueId == null) return;
    SyntaxTree.OccurrenceData valueOcc = firstValueReference(operands.get(operands.size() - 1));
    if (valueOcc == null) return;
    String returnId = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_RETURN);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", "return");
    props.put("symbol", valueOcc.symbol);
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("kind", GraphModel.VALUE_KIND_RETURN);
    props.put("access", "write");
    writer.addNode(GraphModel.LABEL_VALUE, returnId, props);
    String scope = innermostCond();
    if (scope != null) {
      writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, returnId, GraphModel.LABEL_CONDITION, scope);
    }
    writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_VALUE, returnId);
    // Record this return slot against the enclosing method for cross-method return binding.
    String methodSymbol = methodSymbols.isEmpty() ? null : methodSymbols.peek();
    if (methodSymbol != null && !methodSymbol.isEmpty()) {
      returnsByMethod.computeIfAbsent(methodSymbol, k -> new ArrayList<>()).add(returnId);
    }
  }

  // ---------------------------------------------------------------------------
  // Call layer
  // ---------------------------------------------------------------------------

  private void enterInvocation(String file, SyntaxTree.Node node, SyntaxTree.Node parent) {
    String symbol = invocationSymbol(node);
    if (symbol == null) return;
    String id = runtimeId(project, file, node.range, null);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", shortName(symbol));
    props.put("symbol", symbol);
    props.put("file", file);
    props.put("line", node.range == null ? 0 : node.range.startLine());
    writer.addNode(GraphModel.LABEL_CALLED_METHOD, id, props);

    if (symbols.containsKey(symbol)) {
      String target = declId(project, file, symbol);
      writer.addEdge(GraphModel.REL_CALLS, GraphModel.LABEL_CALLED_METHOD, id, GraphModel.LABEL_METHOD, target);
    }

    String scope = innermostCond();
    if (scope != null) {
      writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_CALLED_METHOD, id, GraphModel.LABEL_CONDITION, scope);
      if (!isMethodRoot(scope)) {
        writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_CALLED_METHOD, id);
      }
    }

    // Runtime value nodes for the arguments → ARG_OF; the argument's value flows into the slot.
    List<SyntaxTree.Node> args = argumentNodes(node);
    java.util.List<String> calledParamIds = new ArrayList<>();
    int argIndex = 0;
    for (SyntaxTree.Node arg : args) {
      String valueSymbol = argValueSymbol(arg);
      String valueId =
          runtimeId(project, file, arg.range, argIndex + ":" + (valueSymbol != null ? valueSymbol : "arg"));
      Map<String, Object> argProps = new LinkedHashMap<>();
      argProps.put("name", valueSymbol != null && !valueSymbol.isEmpty() ? valueName(file, valueSymbol) : "arg");
      argProps.put("symbol", valueSymbol != null ? valueSymbol : "");
      argProps.put("file", file);
      argProps.put("line", arg.range == null ? 0 : arg.range.startLine());
      argProps.put("kind", GraphModel.VALUE_KIND_CALLED_PARAM);
      writer.addNode(GraphModel.LABEL_VALUE, valueId, argProps);
      writer.addEdge(GraphModel.REL_ARG_OF, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CALLED_METHOD, id);
      if (scope != null) {
        writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CONDITION, scope);
      }
      // The argument expression's value flows into this call slot.
      String argSourceId = firstValueRuntimeId(file, arg);
      if (argSourceId != null && !argSourceId.equals(valueId)) {
        writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, argSourceId, GraphModel.LABEL_VALUE, valueId);
      }
      calledParamIds.add(valueId);
      argIndex++;
    }

    // Cross-method param binding: arg slot i → callee's i-th parameter declaration.
    if (symbols.containsKey(symbol)) {
      java.util.List<String> params = paramsByMethod.get(symbol);
      if (params != null) {
        int n = Math.min(calledParamIds.size(), params.size());
        for (int i = 0; i < n; i++) {
          String paramId = declId(project, "", params.get(i));
          writer.addEdge(
              GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, calledParamIds.get(i), GraphModel.LABEL_VALUE, paramId);
        }
      }
    }

    // Call result (calledReturn) → flows into the assignment LHS when this call is the RHS.
    String callReturnId = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_CALLED_RETURN);
    Map<String, Object> retProps = new LinkedHashMap<>();
    retProps.put("name", shortName(symbol) + "#");
    retProps.put("symbol", symbol);
    retProps.put("file", file);
    retProps.put("line", node.range == null ? 0 : node.range.startLine());
    retProps.put("kind", GraphModel.VALUE_KIND_CALLED_RETURN);
    writer.addNode(GraphModel.LABEL_VALUE, callReturnId, retProps);
    if (scope != null) {
      writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, callReturnId, GraphModel.LABEL_CONDITION, scope);
    }
    // Cross-method return binding: the callee's return slots flow into this call's result.
    if (symbols.containsKey(symbol)) {
      java.util.List<String> returns = returnsByMethod.get(symbol);
      if (returns != null) {
        for (String r : returns) {
          if (!r.equals(callReturnId)) {
            writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, r, GraphModel.LABEL_VALUE, callReturnId);
          }
        }
      }
    }
    if (parent != null && isAssignment(parent)) {
      List<SyntaxTree.Node> operands = assignmentOperands(parent);
      if (operands.size() >= 2) {
        String lhsId = firstValueRuntimeId(file, operands.get(0));
        if (lhsId != null) {
          writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, callReturnId, GraphModel.LABEL_VALUE, lhsId);
        }
      }
    }

    // REF: the receiver expression's value references the call (nesting direction).
    for (String receiverId : receiverValueIds(file, node)) {
      writer.addEdge(GraphModel.REL_REF, GraphModel.LABEL_VALUE, receiverId, GraphModel.LABEL_CALLED_METHOD, id);
    }
  }

  private List<String> receiverValueIds(String file, SyntaxTree.Node node) {
    List<String> out = new ArrayList<>();
    SyntaxTree.Node receiver = receiverSubtree(node);
    if (receiver == null) return out;
    collectValueIds(file, receiver, out);
    return out;
  }

  private void collectValueIds(String file, SyntaxTree.Node subtree, List<String> out) {
    for (SyntaxTree.OccurrenceData occ : subtree.occurrences) {
      if (occ.role == 0 && valueKindFor(occ.syntaxKind) != null) {
        out.add(runtimeId(project, file, occ.range, valueKindFor(occ.syntaxKind)));
      }
    }
    for (SyntaxTree.Node child : subtree.children) {
      collectValueIds(file, child, out);
    }
  }

  /** The receiver chain child of an invocation (the object expression being called on), or null. */
  private static SyntaxTree.Node receiverSubtree(SyntaxTree.Node node) {
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("DOT_QUALIFIED_EXPRESSION")
          || child.kind.equals("SAFE_ACCESS_EXPRESSION")
          || child.kind.equals("MEMBER_SELECT")) {
        return child;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Conditions
  // ---------------------------------------------------------------------------

  private void enterCondition(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    String id = runtimeId(project, file, node.range, null);
    String kind =
        isLoopKind(node.kind) ? GraphModel.CONDITION_KIND_LOOP : GraphModel.CONDITION_KIND_IF;
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("file", file);
    props.put("line", node.range == null ? 0 : node.range.startLine());
    props.put("kind", kind);
    writer.addNode(GraphModel.LABEL_CONDITION, id, props);

    String parentCond = innermostCond();
    if (parentCond != null) {
      writer.addEdge(GraphModel.REL_SUB, GraphModel.LABEL_CONDITION, parentCond, GraphModel.LABEL_CONDITION, id);
    }
    // else-if chain: Kotlin wraps the else branch in an ELSE node; javac puts it at child index >= 2.
    boolean isElsePosition =
        (parent != null && parent.kind.equals("ELSE"))
            || (parent != null && isConditionKind(parent.kind) && index >= 2);
    if (isElsePosition && parentCond != null) {
      writer.addEdge(GraphModel.REL_ELSE, GraphModel.LABEL_CONDITION, parentCond, GraphModel.LABEL_CONDITION, id);
    }

    conds.push(id);

    // CONTROLS: values referenced in the condition expression guard this branch.
    for (String valueId : conditionValueIds(file, node)) {
      writer.addEdge(GraphModel.REL_CONTROLS, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CONDITION, id);
    }
  }

  private String innermostCond() {
    return conds.isEmpty() ? null : conds.peek();
  }

  private boolean isMethodRoot(String condId) {
    return !methodRootConds.isEmpty() && condId.equals(methodRootConds.peek());
  }

  private List<String> conditionValueIds(String file, SyntaxTree.Node node) {
    SyntaxTree.Node expr = conditionExpression(node);
    List<String> out = new ArrayList<>();
    if (expr != null) collectValueIds(file, expr, out);
    return out;
  }

  private static SyntaxTree.Node conditionExpression(SyntaxTree.Node node) {
    if (node.kind.equals("IF")) {
      for (SyntaxTree.Node child : node.children) {
        if (!child.kind.equals("WHITE_SPACE")
            && !child.kind.equals("THEN")
            && !child.kind.equals("ELSE")
            && !child.kind.equals("else")) {
          return child;
        }
      }
      return null;
    }
    // Loops: javac first child; Kotlin the CONDITION child.
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("CONDITION")) return child;
    }
    for (SyntaxTree.Node child : node.children) {
      if (!child.kind.equals("WHITE_SPACE")) return child;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Kind classification (javac + Kotlin PSI)
  // ---------------------------------------------------------------------------

  private static boolean isTypeKind(String kind) {
    return kind.equals("CLASS")
        || kind.equals("INTERFACE")
        || kind.equals("ENUM")
        || kind.equals("RECORD")
        || kind.equals("ANNOTATION_TYPE")
        || kind.equals("OBJECT_DECLARATION")
        || kind.equals("OBJECT_LITERAL")
        || kind.equals("TYPEALIAS")
        || kind.equals("companion");
  }

  private static boolean isMethodKind(String kind) {
    return kind.equals("METHOD")
        || kind.equals("FUN")
        || kind.equals("SECONDARY_CONSTRUCTOR")
        || kind.equals("PRIMARY_CONSTRUCTOR");
  }

  private static boolean isConditionKind(String kind) {
    return kind.equals("IF")
        || kind.equals("WHILE_LOOP")
        || kind.equals("FOR_LOOP")
        || kind.equals("ENHANCED_FOR_LOOP")
        || kind.equals("WHILE")
        || kind.equals("FOR")
        || kind.equals("DO_WHILE")
        || kind.equals("WHEN");
  }

  private static boolean isLoopKind(String kind) {
    return kind.equals("WHILE_LOOP")
        || kind.equals("FOR_LOOP")
        || kind.equals("ENHANCED_FOR_LOOP")
        || kind.equals("WHILE")
        || kind.equals("FOR")
        || kind.equals("DO_WHILE");
  }

  private static boolean isInvocationKind(String kind) {
    return kind.equals("METHOD_INVOCATION")
        || kind.equals("NEW_CLASS")
        || kind.equals("CALL_EXPRESSION")
        || kind.equals("CONSTRUCTOR_CALL");
  }

  private static boolean isMemberSelectKind(String kind) {
    return kind.equals("DOT_QUALIFIED_EXPRESSION")
        || kind.equals("SAFE_ACCESS_EXPRESSION")
        || kind.equals("MEMBER_SELECT");
  }

  private static boolean isIndexAccessKind(String kind) {
    return kind.equals("ARRAY_ACCESS_EXPRESSION") || kind.equals("ARRAY_ACCESS");
  }

  /**
   * Array access {@code arr[i]} (the old viewer's Index relation): the array value references the
   * indexed element access, mirroring {@code addIndexProlog} ({@code array → index → element}).
   */
  private void handleIndexAccess(String file, SyntaxTree.Node node) {
    SyntaxTree.Node array = firstOperand(node);
    if (array == null) return;
    SyntaxTree.OccurrenceData base = firstValueReference(array);
    if (base == null) return;
    String kind = valueKindFor(base.syntaxKind);
    if (kind == null) return;
    String baseId = runtimeId(project, file, base.range, kind);

    String elementId = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_INDEX);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", "[]");
    props.put("symbol", base.symbol);
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("kind", GraphModel.VALUE_KIND_INDEX);
    writer.addNode(GraphModel.LABEL_VALUE, elementId, props);
    String scope = innermostCond();
    if (scope != null) {
      writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, elementId, GraphModel.LABEL_CONDITION, scope);
    }
    if (!baseId.equals(elementId)) {
      writer.addEdge(GraphModel.REL_INDEX, GraphModel.LABEL_VALUE, baseId, GraphModel.LABEL_VALUE, elementId);
    }
  }

  private static boolean isReturnKind(String kind) {
    return kind.equals("RETURN");
  }

  private static boolean isAssignment(SyntaxTree.Node node) {
    if (node.kind.equals("ASSIGNMENT")) return true;
    if (node.kind.equals("BINARY_EXPRESSION")) {
      for (SyntaxTree.Node child : node.children) {
        if (child.kind.equals("OPERATION_REFERENCE") && isAssignOperation(child)) return true;
      }
    }
    return false;
  }

  private static boolean isAssignOperation(SyntaxTree.Node opRef) {
    for (SyntaxTree.Node child : opRef.children) {
      switch (child.kind) {
        case "EQ", "PLUSEQ", "MINUSEQ", "MULTEQ", "DIVEQ", "PERCEQ", "ANDEQ", "OREQ", "XOREQ",
            "SHL", "SHR", "USHR" -> {
          return true;
        }
        default -> {}
      }
    }
    return false;
  }

  private static List<SyntaxTree.Node> nonWhitespaceChildren(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    for (SyntaxTree.Node child : node.children) {
      if (!child.kind.equals("WHITE_SPACE")) out.add(child);
    }
    return out;
  }

  /**
   * The branch subtrees of a condition node: javac IF → then/else statements; Kotlin IF → THEN and
   * ELSE children; Kotlin WHEN → WHEN_ENTRY children; loops → the body (last child).
   */
  private static List<SyntaxTree.Node> branchChildren(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    List<SyntaxTree.Node> kids = nonWhitespaceChildren(node);
    if (node.kind.equals("IF")) {
      for (int i = 1; i < kids.size(); i++) out.add(kids.get(i));
    } else if (node.kind.equals("WHEN")) {
      for (SyntaxTree.Node k : kids) {
        if (k.kind.equals("WHEN_ENTRY")) out.add(k);
      }
    } else {
      if (!kids.isEmpty()) out.add(kids.get(kids.size() - 1));
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Occurrence helpers
  // ---------------------------------------------------------------------------

  private static SyntaxTree.OccurrenceData definition(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 1) return occ;
    }
    return null;
  }

  /** The structural node's own definition (itself or a direct child). */
  private static SyntaxTree.OccurrenceData structuralDefinition(SyntaxTree.Node node) {
    SyntaxTree.OccurrenceData own = definition(node);
    if (own != null) return own;
    for (SyntaxTree.Node child : node.children) {
      SyntaxTree.OccurrenceData d = definition(child);
      if (d != null) return d;
    }
    return null;
  }

  private static String invocationSymbol(SyntaxTree.Node node) {
    return functionReferenceIn(node);
  }

  /** First function-syntax reference in pre-order (skips value arguments). */
  private static String functionReferenceIn(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 0 && isFunctionSyntax(occ.syntaxKind)) return occ.symbol;
    }
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("VALUE_ARGUMENT_LIST")) continue;
      String s = functionReferenceIn(child);
      if (s != null) return s;
    }
    return null;
  }

  private static boolean isFunctionSyntax(String syntaxKind) {
    if (syntaxKind == null) return false;
    return syntaxKind.equals("IdentifierFunction")
        || syntaxKind.equals("IdentifierFunctionDefinition");
  }

  private static List<SyntaxTree.Node> argumentNodes(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    String calleeSymbol = invocationSymbol(node);
    SyntaxTree.Node receiver = receiverSubtree(node);
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("VALUE_ARGUMENT_LIST")) {
        for (SyntaxTree.Node va : child.children) {
          if (va.kind.equals("VALUE_ARGUMENT")) out.add(va);
        }
      } else if (child == receiver
          || child.kind.equals("OPERATION_REFERENCE")
          || child.kind.equals("WHITE_SPACE")) {
        continue;
      } else if (calleeSymbol == null || !hasSymbol(child, calleeSymbol)) {
        out.add(child);
      }
    }
    return out;
  }

  private static boolean hasSymbol(SyntaxTree.Node node, String symbol) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (symbol.equals(occ.symbol)) return true;
    }
    return false;
  }

  private static String argValueSymbol(SyntaxTree.Node arg) {
    for (SyntaxTree.OccurrenceData occ : arg.occurrences) {
      if (occ.role == 0 && occ.symbol != null && !occ.symbol.isEmpty()) return occ.symbol;
    }
    for (SyntaxTree.Node child : arg.children) {
      String s = argValueSymbol(child);
      if (s != null) return s;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // SCIP symbol hierarchy
  // ---------------------------------------------------------------------------

  static String ownerOf(String symbol) {
    if (symbol == null || symbol.isEmpty()) return null;
    if (symbol.endsWith(").")) {
      int depth = 0;
      int open = -1;
      for (int i = symbol.length() - 2; i >= 0; i--) {
        char c = symbol.charAt(i);
        if (c == ')') depth++;
        else if (c == '(') {
          depth--;
          if (depth == 0) {
            open = i;
            break;
          }
        }
      }
      if (open < 0) return null;
      int j = open - 1;
      while (j >= 0 && isNameChar(symbol.charAt(j))) j--;
      return j < 0 ? null : symbol.substring(0, j + 1);
    }
    if (symbol.endsWith(")")) {
      int i = symbol.length() - 2;
      while (i >= 0 && symbol.charAt(i) != '(') i--;
      return i < 0 ? null : symbol.substring(0, i);
    }
    if (symbol.endsWith("#")) {
      int i = symbol.length() - 2;
      while (i >= 0 && isNameChar(symbol.charAt(i))) i--;
      return i < 0 ? null : symbol.substring(0, i + 1);
    }
    if (symbol.endsWith(".")) {
      int i = symbol.length() - 2;
      while (i >= 0 && isNameChar(symbol.charAt(i))) i--;
      return i < 0 ? null : symbol.substring(0, i + 1);
    }
    return null;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '`' || c == '$' || c == '-' || c == '+';
  }

  // ---------------------------------------------------------------------------
  // Misc helpers
  // ---------------------------------------------------------------------------

  private static String displayName(SymbolInformation info, String symbol) {
    if (info != null && !info.getDisplayName().isEmpty()) return info.getDisplayName();
    return shortName(symbol);
  }

  /**
   * Display name of a runtime value reference: locals use the per-file source name (old viewer's
   * variableKey), globals (params/fields) the {@link SymbolInformation} display name. Falls back to
   * {@link #shortName} when the symbol carries no name information.
   */
  private String valueName(String file, String symbol) {
    if (symbol == null || symbol.isEmpty()) return symbol;
    if (ScipSymbols.isLocal(symbol)) {
      Map<String, String> fileNames = localNamesByFile.get(file);
      String n = fileNames == null ? null : fileNames.get(symbol);
      if (n != null && !n.isEmpty()) return n;
      return shortName(symbol);
    }
    SymbolInformation info = symbols.get(symbol);
    if (info != null && !info.getDisplayName().isEmpty()) return info.getDisplayName();
    return shortName(symbol);
  }

  private static String typeKind(SymbolInformation info) {
    if (info == null) return "class";
    return switch (info.getKind()) {
      case Interface -> "interface";
      case Enum -> "enum";
      case TypeParameter -> "type";
      default -> "class";
    };
  }

  private static int rangeLine(SyntaxTree.OccurrenceData occ) {
    return occ.range == null ? 0 : occ.range.startLine();
  }

  private static int rangeLine(SyntaxTree.Node node) {
    return node.range == null ? 0 : node.range.startLine();
  }

  static String shortName(String symbol) {
    if (symbol.startsWith("local ")) return symbol.substring("local ".length());
    int hash = symbol.lastIndexOf('#');
    int slash = symbol.lastIndexOf('/');
    int cut = Math.max(hash, slash);
    String tail = cut >= 0 ? symbol.substring(cut + 1) : symbol;
    if (tail.endsWith("()")) return tail.substring(0, tail.length() - 2);
    if (tail.endsWith(".")) return tail.substring(0, tail.length() - 1);
    int paren = tail.indexOf('(');
    if (paren > 0) return tail.substring(0, paren);
    return tail;
  }

  /**
   * The dot-separated package of a class from its SCIP symbol, e.g.
   * {@code "…okhttp3/internal/connection/RealCall#"} → {@code "okhttp3.internal.connection"}.
   * Empty string for the default package.
   */
  static String packageOf(String symbol) {
    if (symbol == null) return "";
    int hash = symbol.lastIndexOf('#');
    if (hash < 0) return "";
    int i = hash - 1;
    while (i >= 0 && isNameChar(symbol.charAt(i))) i--;
    if (i < 0) return "";
    String before = symbol.substring(0, i + 1);
    int sp = before.lastIndexOf(' ');
    String pkg = sp >= 0 ? before.substring(sp + 1) : before;
    return pkg.replace('/', '.');
  }
}

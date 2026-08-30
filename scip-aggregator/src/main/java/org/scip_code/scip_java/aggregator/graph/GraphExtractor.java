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
 * reaches a call), {@code ROOT}/{@code SUB}/{@code ELSE}/{@code LEADS_TO} (branches; {@code
 * LEADS_TO} also anchors each runtime node — call site or Value — to its enclosing condition /
 * method root, all in the unified Condition->node direction), {@code CALLS}/{@code ARG_OF} (calls).
 */
public final class GraphExtractor {

  private final GraphSink writer;
  private final String project;
  private final Map<String, SymbolInformation> symbols;

  // Post-pass bookkeeping: created declaration symbols → node label.
  private final Map<String, String> createdSymbolLabel = new LinkedHashMap<>();
  // In-project Method node ids actually created (from method definitions). A CALLS target NOT in
  // this set is a dependency / unknown symbol → a placeholder Method node is materialised for it in
  // emitRelationships(), so every call site still expands to at least one searchable node.
  private final java.util.Set<String> inProjectMethodIds = new java.util.HashSet<>();
  private final java.util.Map<String, String> callTargetSymbols = new java.util.HashMap<>();
  // Collect CALLS (callId, targetId) and emit them at the end (emitExternalMethodTargets), AFTER
  // any placeholder Method targets are queued, so batch flushes never write an edge to a missing node.
  private final java.util.List<java.util.List<String>> deferredCalls = new java.util.ArrayList<>();

  private final Deque<String> methodRootConds = new ArrayDeque<>();
  private final Deque<String> conds = new ArrayDeque<>();
  private final Deque<String> methodSymbols = new ArrayDeque<>();
  // Cross-method binding: callee method symbol → its params in declaration order.
  private final Map<String, java.util.List<String>> paramsByMethod = new java.util.HashMap<>();
  // CALLED_PARAM 槽的形参后置补正：paramsByMethod 在处理到被调文件时才填充，调用点可能在它
  // 之前被处理，故先记录，待全部文件提取完、paramsByMethod 完整后再统一把槽名/符号改成对应形参。
  private static final class ArgSlotFixup {
    final String id; final String file; final String calleeSymbol; final int argIndex;
    ArgSlotFixup(String id, String file, String calleeSymbol, int argIndex) {
      this.id = id; this.file = file; this.calleeSymbol = calleeSymbol; this.argIndex = argIndex;
    }
  }
  private final java.util.List<ArgSlotFixup> pendingArgFixups = new java.util.ArrayList<>();
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

  // ---------------------------------------------------------------------------
  // Execution-order chain state (event-level NEXT, cross-block/cross-function)
  // ---------------------------------------------------------------------------

  /** A chain end waiting to link to the next event in an enclosing block. */
  private static final class Join {
    final String id;
    final String label;

    Join(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  /** A runtime event in an order chain, with its node label. */
  private static final class EventRef {
    final String id;
    final String label;

    EventRef(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  /** Builds one block's event-level order chain. */
  private static final class BlockBuilder {
    final List<EventRef> events = new ArrayList<>();
    final List<Join> pendingJoins = new ArrayList<>();
    EventRef startFrom = null; // event this block continues from (enclosing chain's last event)

    EventRef lastEvent() {
      return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    List<Join> finish() {
      List<Join> ends = new ArrayList<>(pendingJoins);
      EventRef last = lastEvent();
      if (last != null) {
        ends.add(new Join(last.id, last.label));
      }
      pendingJoins.clear();
      return ends;
    }
  }

  private final Deque<BlockBuilder> blockStack = new ArrayDeque<>();
  // Branch kind of the block being walked: "then" (entered via NEXT) or "else" (entered via ELSE).
  private final Deque<String> branchKinds = new ArrayDeque<>();
  // The most recent Condition event; branch blocks link their first event from it (NEXT=then /
  // ELSE=else).
  private EventRef lastConditionEvent = null;
  private EventRef pendingBranchStartFrom = null;
  // Cross-function order: callee symbol → its body's first / exit events.
  private final Map<String, java.util.List<EventRef>> methodFirstEvents = new java.util.HashMap<>();
  private final Map<String, java.util.List<EventRef>> methodExitEvents = new java.util.HashMap<>();
  // Call sites collected for the cross-function order post-pass.
  private static final class CallSite {
    final String calleeSymbol;
    final String calledMethodId;
    final String calledReturnId;

    CallSite(String calleeSymbol, String calledMethodId, String calledReturnId) {
      this.calleeSymbol = calleeSymbol;
      this.calledMethodId = calledMethodId;
      this.calledReturnId = calledReturnId;
    }
  }

  private final List<CallSite> callSites = new ArrayList<>();
  // 条件节点的线性入链延迟到子节点(守卫表达式读取)走完后，保证 守卫读 → IF → 分支 的顺序。
  private final Deque<EventRef> pendingConditionChains = new ArrayDeque<>();
  // Deferred per-invocation chain events (arg slots → calledMethod → calledReturn), chained at the
  // invocation node's exit so arg-internal reads (chained during child traversal) precede the call
  // in execution order — matching eval-order: ... → argExpr reads → args → call → return → ...
  private final Deque<java.util.List<EventRef>> pendingCallChains = new ArrayDeque<>();
  // 赋值/声明的"写"延迟到 RHS 读之后再入链：`x = rhs` / `val x = rhs` 执行顺序是"先求值 RHS（读），
  // 再写 LHS"。Write 在遍历 LHS（或声明）时最先遇到，若立刻入链会把写排到 RHS 读之前。
  // 用"行"作语句边界：同一行内的写先挂着，待链推进到下一行（该语句的 RHS 读及之后的语句）再统一入链。
  private static final class LocalWrite {
    final int line; final String id; final String label;
    LocalWrite(int line, String id, String label) { this.line = line; this.id = id; this.label = label; }
  }
  private final java.util.List<LocalWrite> pendingLocalWrites = new java.util.ArrayList<>();

  /** 在并入一个位于 {@code line} 的新事件前，先把行号更小的延迟"写"入链（同语句更后的读不受影响）。 */
  private void flushLocalWrites(String file, int line) {
    for (int i = 0; i < pendingLocalWrites.size(); ) {
      LocalWrite w = pendingLocalWrites.get(i);
      if (w.line < line) { pendingLocalWrites.remove(i); appendChainEvent(file, w.id, w.label, w.line); }
      else i++;
    }
  }
  private void flushAllLocalWrites(String file) { flushLocalWrites(file, Integer.MAX_VALUE); }

  /** 入链一个运行时事件（带源行，供按行冲排延迟写）。 */
  private void appendChainEvent(String file, String id, String label, int line) {
    flushLocalWrites(file, line);
    doAppendChainEvent(file, id, label);
  }

  /**
   * Append a runtime event to the current block's order chain, linking it from the previous event
   * and resolving any pending branch joins. {@code label} is the event node's label.
   */
  private void doAppendChainEvent(String file, String id, String label) {
    // 所有运行时事件（方法调用、value 读取/写入、实参槽、条件、返回值、索引元素）都进执行链，
    // 保证 method↔value、value↔value 连续、节点不孤立。实参槽在调用前入链，实参子表达式的读取
    // 在遍历到子节点时补链，整体连通。
    BlockBuilder b = blockStack.peek();
    if (b == null) return;
    boolean hasJoins = !b.pendingJoins.isEmpty();
    for (Join j : b.pendingJoins) {
      writer.addEdge(GraphModel.REL_NEXT, j.label, j.id, label, id);
    }
    b.pendingJoins.clear();
    EventRef prev = b.lastEvent();
    if (prev != null) {
      if (!hasJoins && !prev.id.equals(id)) {
        writer.addEdge(GraphModel.REL_NEXT, prev.label, prev.id, label, id);
      }
    } else if (b.startFrom != null) {
      // Branch entry: a branch block's first event links from the condition — NEXT for the
      // then branch, ELSE for the else branch.
      String kind = branchKinds.isEmpty() ? null : branchKinds.peek();
      String rel = "else".equals(kind) ? GraphModel.REL_ELSE : GraphModel.REL_NEXT;
      writer.addEdge(rel, b.startFrom.label, b.startFrom.id, label, id);
      b.startFrom = null;
    }
    b.events.add(new EventRef(id, label));
    // First event of the outermost (method body) block → cross-function entry.
    if (blockStack.size() == 1 && b.events.size() == 1) {
      String m = methodSymbols.isEmpty() ? null : methodSymbols.peek();
      if (m != null && !m.isEmpty()) {
        methodFirstEvents.computeIfAbsent(m, k -> new ArrayList<>()).add(new EventRef(id, label));
      }
    }
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
    /**
   * 被调函数的按序形参名。优先项目内记录（paramsByMethod，成员本身是形参符号）；外部方法在
   * collectedSymbols 里有签名文档（编译期已知），解析签名 `(a: T, b: U)` 取形参名，不退回 "#N"。
   */
  private java.util.List<String> paramNamesOf(String calleeSymbol) {
    java.util.List<String> syms = paramsByMethod.get(calleeSymbol);
    if (syms != null && !syms.isEmpty()) {
      // paramsByMethod 存的是形参符号，转成显示名（如 "request"）。
      java.util.List<String> names = new ArrayList<>();
      for (String s : syms) {
        SymbolInformation pi = symbols.get(s);
        names.add(pi != null && !pi.getDisplayName().isEmpty() ? pi.getDisplayName() : shortName(s));
      }
      return names;
    }
    SymbolInformation info = symbols.get(calleeSymbol);
    if (info != null && info.hasSignatureDocumentation()) {
      String sig = info.getSignatureDocumentation().getText();
      int open = sig.indexOf('('), close = sig.lastIndexOf(')');
      if (open >= 0 && close > open) {
        java.util.List<String> out = new ArrayList<>();
        for (String seg : sig.substring(open + 1, close).split(",", -1)) {
          String t = seg.trim();
          if (t.isEmpty()) continue;
          int ci = t.indexOf(':');
          String n = (ci > 0 ? t.substring(0, ci) : t).trim();
          if (!n.isEmpty()) out.add(n);
        }
        if (!out.isEmpty()) return out;
      }
    }
    return null;
  }

  /** 由形参名合成 SCIP 形参符号：`方法().(参数名)`（外部方法无独立形参 SymbolInformation 时用）。 */
  private static String paramSymbolFor(String calleeSymbol, String paramName) {
    if (calleeSymbol == null || paramName == null || paramName.isEmpty()) return null;
    return calleeSymbol.endsWith("().") ? calleeSymbol + "(" + paramName + ")" : null;
  }

  /** 全部文件提取完后，把 CALLED_PARAM 槽的符号/名字后置补正为被调函数对应形参（paramsByMethod 此时已完整）。 */
  private void fixupArgSlots() {
    for (ArgSlotFixup f : pendingArgFixups) {
      java.util.List<String> ps = paramNamesOf(f.calleeSymbol);
      if (ps == null || f.argIndex >= ps.size()) continue;
      String pName = ps.get(f.argIndex);
      String pSym = paramSymbolFor(f.calleeSymbol, pName);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", pName);
      if (pSym != null) m.put("symbol", pSym);
      writer.addNode(GraphModel.LABEL_VALUE, f.id, m); // MERGE by id → 更新该槽的 name/symbol
    }
    pendingArgFixups.clear();
  }

  public void emitRelationships() {
    fixupArgSlots();
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
    emitOrderRelationships();
    emitExternalMethodTargets();
  }

  /**
   * 后置补全：所有 CALLS 目标里，凡不是项目内已建的 Method 节点（依赖/未建定义的），为其补一个
   * external 占位 Method 节点。这样每个调用点至少能展开到一个可搜索的节点（即使被调在依赖包里），
   * 不再让 CALLS 指向不存在的节点而被丢弃。出现在 emitRelationships 末尾，此时项目内定义全已建好，
   * 也避免了"调用先于定义"导致的误判。
   */
  private void emitExternalMethodTargets() {
    // 1) 先把项目内定义之外的所有 CALLS 目标补成 external 占位 Method 节点（入队待 flush）。
    for (Map.Entry<String, String> en : callTargetSymbols.entrySet()) {
      String target = en.getKey();
      if (inProjectMethodIds.contains(target)) continue;
      String esym = en.getValue();
      if (ScipSymbols.isLocal(esym)) continue;
      Map<String, Object> ep = new LinkedHashMap<>();
      ep.put("name", shortName(esym));
      ep.put("symbol", esym);
      ep.put("external", true);
      writer.addNode(GraphModel.LABEL_METHOD, target, ep);
    }
    // 2) 再统一补发所有 CALLS 边。flush() 会先在 pendingNodes 里写占位/项目内 Method 节点、
    //    后写这些边，故 MATCH 目标必然存在。
    for (java.util.List<String> c : deferredCalls) {
      writer.addEdge(
          GraphModel.REL_CALLS, GraphModel.LABEL_CALLED_METHOD, c.get(0), GraphModel.LABEL_METHOD, c.get(1));
    }
  }

  private static boolean isTypeSymbol(SymbolInformation.Kind kind) {
    return kind == SymbolInformation.Kind.Class
        || kind == SymbolInformation.Kind.Interface
        || kind == SymbolInformation.Kind.Enum;
  }

  /**
   * Cross-function order (called at the end of {@link #emitRelationships()}): a call site enters
   * the callee's first event, and the callee's exit events flow back into the caller's
   * calledReturn — so order paths continue across function boundaries without dangling.
   */
  private void emitOrderRelationships() {
    for (CallSite cs : callSites) {
      if (!symbols.containsKey(cs.calleeSymbol)) continue;
      java.util.List<EventRef> firsts = methodFirstEvents.get(cs.calleeSymbol);
      if (firsts != null && !firsts.isEmpty()) {
        EventRef f = firsts.get(0);
        writer.addEdge(
            GraphModel.REL_NEXT,
            GraphModel.LABEL_CALLED_METHOD, cs.calledMethodId,
            f.label, f.id);
      }
      java.util.List<EventRef> exits = methodExitEvents.get(cs.calleeSymbol);
      if (exits != null) {
        for (EventRef ex : exits) {
          if (!ex.id.equals(cs.calledReturnId)) {
            writer.addEdge(
                GraphModel.REL_NEXT,
                ex.label, ex.id,
                GraphModel.LABEL_VALUE, cs.calledReturnId);
          }
        }
      }
    }
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
    // 守卫表达式读取已入链：现在把 IF 条件节点线性入链（守卫读 → IF），再走分支（分支从 IF 锚定）。
    if (!pendingConditionChains.isEmpty()) {
      EventRef e = pendingConditionChains.pop();
      appendChainEvent(file, e.id, e.label, rangeLine(node));
    }

    List<Scope> branchScopes = new ArrayList<>();
    for (int i = 0; i < branches.size(); i++) {
      // Then branch is entered via NEXT from the condition; else branches via ELSE.
      String kind = isLoopKind(node.kind) ? "then" : (i == 0 ? "then" : "else");
      branchKinds.push(kind);
      if ("else".equals(kind)) {
        // else 分支也物化成一个 kind=ELSE 的 Condition 节点：IF --ELSE--> ELSE(node)，
        // 并把 else 块首节点的起点锚到该 ELSE 节点（else 不是边，是 Condition 的一种）。
        SyntaxTree.Node br = branches.get(i);
        String elseId = runtimeId(project, file, br.range, "ELSE");
        Map<String, Object> ep = new LinkedHashMap<>();
        ep.put("file", file);
        ep.put("line", rangeLine(br));
        ep.put("col", rangeCol(br));
        ep.put("colEnd", rangeColEnd(br));
        ep.put("kind", GraphModel.CONDITION_KIND_ELSE);
        writer.addNode(GraphModel.LABEL_CONDITION, elseId, ep);
        if (lastConditionEvent != null) {
          writer.addEdge(GraphModel.REL_ELSE, lastConditionEvent.label, lastConditionEvent.id,
              GraphModel.LABEL_CONDITION, elseId);
        }
        pendingBranchStartFrom = new EventRef(elseId, GraphModel.LABEL_CONDITION);
      } else {
        pendingBranchStartFrom = lastConditionEvent;
      }
      pushBranchScope();
      walk(file, branches.get(i), node, children.indexOf(branches.get(i)));
      branchScopes.add(scopeStack.pop());
      pendingBranchStartFrom = null;
      branchKinds.pop();
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
    // Method scope: a structural method node (javac METHOD / Kotlin FUN) anchors a root condition;
    // both body-level calls and runtime Value nodes are anchored to it via LEADS_TO. Always pushed
    // (even without a resolvable symbol) so exit() is symmetric.
    if (isMethodKind(node.kind)) {
      pushMethodScope(file, node, structuralDefinition(node));
    }
    if (node.kind.equals("BLOCK")) {
      // 进入块(如 try/嵌套块)前先冲掉更早行的延迟写(如 val x = … 的写)，否则该块的起点/续接
      // 会锚到"写之前"的最后链事件(如 equals()#)，导致后续分支分叉/续接定位到错误节点。
      flushLocalWrites(file, rangeLine(node));
      enterBlock(node);
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

  private void enterBlock(SyntaxTree.Node node) {
    BlockBuilder b = new BlockBuilder();
    if (pendingBranchStartFrom != null) {
      b.startFrom = pendingBranchStartFrom;
      pendingBranchStartFrom = null;
    } else if (!blockStack.isEmpty()) {
      EventRef prev = blockStack.peek().lastEvent();
      if (prev != null) b.startFrom = prev;
    }
    blockStack.push(b);
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
      exitBlock(file);
    }
    // Invocation exit: commit this call's deferred chain events (args → calledMethod → calledReturn)
    // into the enclosing block, after the arg-internal reads that were chained during child walk.
    if (isInvocationKind(node.kind) && !pendingCallChains.isEmpty()) {
      for (EventRef e : pendingCallChains.pop()) {
        appendChainEvent(file, e.id, e.label, rangeLine(node));
      }
    }
  }

  /**
   * On block exit, its chain ends (last event + unresolved branch joins) continue into the
   * enclosing block's next event (via pendingJoin) — or, for the method body, become the method's
   * cross-function exit events.
   */
  private void exitBlock(String file) {
    flushAllLocalWrites(file); // 方法体/块结束前冲掉末尾遗留的延迟写
    BlockBuilder b = blockStack.pop();
    List<Join> ends = b.finish();
    if (blockStack.isEmpty()) {
      String m = methodSymbols.isEmpty() ? null : methodSymbols.peek();
      if (m != null && !m.isEmpty()) {
        for (Join j : ends) {
          methodExitEvents.computeIfAbsent(m, k -> new ArrayList<>()).add(new EventRef(j.id, j.label));
        }
      }
    } else {
      blockStack.peek().pendingJoins.addAll(ends);
    }
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
      props.put("col", rangeCol(def));
      props.put("colEnd", rangeColEnd(def));
      props.put("symbol", symbol);
      boolean isConstructor = info != null && info.getKind() == SymbolInformation.Kind.Constructor;
      props.put("isConstructor", isConstructor);
      if (info != null && info.hasSignatureDocumentation()) {
        props.put("signature", info.getSignatureDocumentation().getText());
      }
      writer.addNode(GraphModel.LABEL_METHOD, id, props);
      inProjectMethodIds.add(id);
      methodSymbols.push(symbol);
    } else {
      methodSymbols.push("");
    }
    // The root condition anchors LEADS_TO for body-level runtime nodes; created in all cases so
    // the enter/exit stacks stay symmetric.
    ScipRange range = def != null && def.range != null ? def.range : node.range;
    String rootCond = runtimeId(project, file, range, "root");
    Map<String, Object> rootProps = new LinkedHashMap<>();
    rootProps.put("file", file);
    rootProps.put("line", range == null ? 0 : range.startLine());
    rootProps.put("col", range == null ? 0 : range.startCharacter());
    rootProps.put("colEnd", range == null ? 0 : range.endCharacter());
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
    // 局部符号（"local N"）按文件区分查找，避免跨文件同号折叠成错误的 displayName。
    SymbolInformation info = infoOf(file, symbol);
    String name = displayName(info, symbol);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", name);
    props.put("file", file);
    props.put("line", rangeLine(def));
    props.put("col", rangeCol(def));
    props.put("colEnd", rangeColEnd(def));
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
      props.put("access", "write"); // 声明 = 一次对该局部变量的写
      if (ScipSymbols.isLocal(symbol)) {
        // Locals use per-file "local N" symbols; remember the real source name for naming reads.
        localNamesByFile
            .computeIfAbsent(file, k -> new java.util.HashMap<>())
            .put(symbol, name);
        // 声明式赋值（val/var x = …）的 LHS 是 definition occurrence，不走 emitReferenceValues，
        // 于是这个"写"节点从未进入 NEXT 执行链、孤立。把它作为一次运行时写事件入链，
        // 与普通 `x = y` 的写节点行为一致，保证每个语句节点都有顺序关系。
        String localId = declId(project, file, symbol);
        writer.addNode(label, localId, props);
        // 写节点延迟（按行）到 RHS 读之后再入链，保证 `val x = rhs` 先读后写。
        pendingLocalWrites.add(new LocalWrite(rangeLine(node), localId, GraphModel.LABEL_VALUE));
        return;
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
      props.put("col", rangeCol(occ));
      props.put("colEnd", rangeColEnd(occ));
      props.put("kind", kind);
      props.put("access", isWrite ? "write" : "read");
      writer.addNode(GraphModel.LABEL_VALUE, id, props);
      if (isWrite) pendingLocalWrites.add(new LocalWrite(rangeLine(occ), id, GraphModel.LABEL_VALUE));
      else appendChainEvent(file, id, GraphModel.LABEL_VALUE, rangeLine(occ));

      String scope = innermostCond();
      if (scope != null) {
        writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_VALUE, id);
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
    // Always create a return slot (even for `return;`), so the order chain has an explicit exit
    // event and the cross-function exit is precise.
    String returnId = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_RETURN);
    SyntaxTree.OccurrenceData valueOcc = null;
    List<SyntaxTree.Node> operands = nonWhitespaceChildren(node);
    if (!operands.isEmpty()) {
      valueOcc = firstValueReference(operands.get(operands.size() - 1));
    }
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", "return");
    props.put("symbol", valueOcc != null ? valueOcc.symbol : "");
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", GraphModel.VALUE_KIND_RETURN);
    props.put("access", "write");
    writer.addNode(GraphModel.LABEL_VALUE, returnId, props);
    String scope = innermostCond();
    if (scope != null) {
      writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_VALUE, returnId);
    }
    if (valueOcc != null) {
      String valueId = firstValueRuntimeId(file, operands.get(operands.size() - 1));
      if (valueId != null && !valueId.equals(returnId)) {
        writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_VALUE, returnId);
      }
    }
    appendChainEvent(file, returnId, GraphModel.LABEL_VALUE, rangeLine(node));
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
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    writer.addNode(GraphModel.LABEL_CALLED_METHOD, id, props);

    String target = declId(project, file, symbol);
    // CALLS 边后置到 emitRelationships() 再发（先把项目内/占位目标节点都入队，flush 先写节点后写
    // 边，边决不指向空节点）。本地符号不跨文件，跳过。
    if (!ScipSymbols.isLocal(symbol)) {
      deferredCalls.add(java.util.List.of(id, target));
      callTargetSymbols.put(target, symbol);
    }

    String scope = innermostCond();
    if (scope != null) {
      // 统一锚定边 LEADS_TO（Condition->运行时节点，恒发含方法根）：调用点（CalledMethod）与
      // Value 数据作用域都走这条边的同一方向。分支/方法归属用 Condition 节点 +
      // kind(METHOD/IF/LOOP/ELSE) 区分即可。
      writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_CALLED_METHOD, id);
    }

    // Runtime value nodes for the arguments → ARG_OF; the argument's value flows into the slot.
    // 实参槽/调用/返回不在 enter 时入链，而是压进 pendingCallChains，在调用节点 exit 时才统一入链
    // （exit 顺序在实参子表达式读取之后），保证执行序 ...→实参求值→实参槽→调用→返回→...。
    java.util.List<EventRef> callChain = new ArrayList<>();
    List<SyntaxTree.Node> args = argumentNodes(node);
    // 被调函数按序的形参符号（用于让实参槽 symbol 与被调函数的形参一致，从 symbol 即知"哪个函数的哪个参数"）
    java.util.List<String> calleeParamSyms = paramsByMethod.get(symbol); // 项目内真实形参符号
    java.util.List<String> calleeParamNames = paramNamesOf(symbol);       // 形参名（项目内或外部签名解析）
    java.util.List<String> calledParamIds = new ArrayList<>();
    int argIndex = 0;
    for (SyntaxTree.Node arg : args) {
      // 实参的取值符号（仅作兜底/伪实参判断）：实参里第一个"非 local"的真实符号。
      String argSym = argLabel(file, arg)[1];
      // 无参/零参调用：AST 可能把"被调函数引用"本身当子节点，误成一个"实参"（其 symbol 恰为 callee），
      // 会给无参函数造出假的 CALLED_PARAM 槽。跳过实参==被调函数自身的项，只在确有其实参时建槽。
      if (argSym != null && argSym.equals(symbol)) continue;
      // 实参槽的 symbol/名字：优先=被调函数的第 argIndex 个形参符号（与 PARAM 节点同源、观感一致）；
      // 形参解析不到（外部/顺序对不上）时回退到实参的取值符号（字段等）。
      String slotSym = null, slotName = null;
      if (calleeParamNames != null && argIndex < calleeParamNames.size()) {
        slotName = calleeParamNames.get(argIndex);
        if (calleeParamSyms != null && argIndex < calleeParamSyms.size()) slotSym = calleeParamSyms.get(argIndex);
        else slotSym = paramSymbolFor(symbol, slotName); // 外部方法：按签名合成 方法().(形参)
      }
      String valueSymbol = slotSym != null ? slotSym : (argSym != null ? argSym : null);
      String safeSym = valueSymbol == null || valueSymbol.isEmpty() ? ("#" + argIndex) : valueSymbol;
      String valueId = runtimeId(project, file, arg.range, argIndex + ":" + safeSym);
      Map<String, Object> argProps = new LinkedHashMap<>();
      String name = slotName != null ? slotName : (argSym != null && !argSym.isEmpty() ? valueName(file, argSym) : ("#" + argIndex));
      argProps.put("name", name);
      argProps.put("symbol", valueSymbol != null ? valueSymbol : "");
      argProps.put("file", file);
      argProps.put("line", arg.range == null ? 0 : arg.range.startLine());
      argProps.put("col", rangeCol(arg));
      argProps.put("colEnd", rangeColEnd(arg));
      argProps.put("kind", GraphModel.VALUE_KIND_CALLED_PARAM);
      writer.addNode(GraphModel.LABEL_VALUE, valueId, argProps);
      callChain.add(new EventRef(valueId, GraphModel.LABEL_VALUE));
      writer.addEdge(GraphModel.REL_ARG_OF, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CALLED_METHOD, id);
      if (scope != null) {
        writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_VALUE, valueId);
      }
      // The argument expression's value flows into this call slot.
      String argSourceId = firstValueRuntimeId(file, arg);
      if (argSourceId != null && !argSourceId.equals(valueId)) {
        writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, argSourceId, GraphModel.LABEL_VALUE, valueId);
      }
      calledParamIds.add(valueId);
      pendingArgFixups.add(new ArgSlotFixup(valueId, file, symbol, argIndex));
      argIndex++;
    }
    // 实参槽之后入链调用本身。
    callChain.add(new EventRef(id, GraphModel.LABEL_CALLED_METHOD));
    pendingCallChains.push(callChain);

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
    retProps.put("col", rangeCol(node));
    retProps.put("colEnd", rangeColEnd(node));
    retProps.put("kind", GraphModel.VALUE_KIND_CALLED_RETURN);
    writer.addNode(GraphModel.LABEL_VALUE, callReturnId, retProps);
    // Deferred with the rest of this call's chain (same list referenced by pendingCallChains top).
    callChain.add(new EventRef(callReturnId, GraphModel.LABEL_VALUE));
    if (scope != null) {
      writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_VALUE, callReturnId);
    }
    // Cross-function order: the callee's exit events flow into this call's result.
    if (symbols.containsKey(symbol)) {
      callSites.add(new CallSite(symbol, id, callReturnId));
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
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", kind);
    writer.addNode(GraphModel.LABEL_CONDITION, id, props);
    // 条件节点暂不入链：把它的线性入链延迟到 exit（守卫表达式读取走完后），保证 守卫读 → IF → 分支。
    pendingConditionChains.push(new EventRef(id, GraphModel.LABEL_CONDITION));
    lastConditionEvent = new EventRef(id, GraphModel.LABEL_CONDITION);

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
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", GraphModel.VALUE_KIND_INDEX);
    writer.addNode(GraphModel.LABEL_VALUE, elementId, props);
    appendChainEvent(file, elementId, GraphModel.LABEL_VALUE, rangeLine(node));
    String scope = innermostCond();
    if (scope != null) {
      writer.addEdge(GraphModel.REL_LEADS_TO, GraphModel.LABEL_CONDITION, scope, GraphModel.LABEL_VALUE, elementId);
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
      // javac: [condition, then, else?]; Kotlin: ['if', condition, THEN, ELSE?]. The condition
      // expression is NOT a branch; Kotlin wraps the branches in THEN/ELSE nodes.
      for (SyntaxTree.Node k : kids) {
        if (k.kind.equals("THEN") || k.kind.equals("ELSE")) out.add(k);
      }
      if (out.isEmpty()) {
        for (int i = 1; i < kids.size(); i++) out.add(kids.get(i));
      }
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
    boolean hasArgList = false;
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("VALUE_ARGUMENT_LIST")) { hasArgList = true; break; }
    }
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("VALUE_ARGUMENT_LIST")) {
        // 有显式实参表：只取其 VALUE_ARGUMENT，不把 receiver/嵌套子表达式误当实参
        for (SyntaxTree.Node va : child.children) {
          if (va.kind.equals("VALUE_ARGUMENT")) out.add(va);
        }
      } else if (child == receiver
          || child.kind.equals("OPERATION_REFERENCE")
          || child.kind.equals("WHITE_SPACE")) {
        continue;
      } else if (!hasArgList && (calleeSymbol == null || !hasSymbol(child, calleeSymbol))) {
        // 树形没有 VALUE_ARGUMENT_LIST（裸参数调用）时才用兜底收集裸参。
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

  /**
   * 实参槽的 {名字, 符号}，遍历语法树（不依赖源码文本）：
   *  名字 = 实参里第一个引用（左起）的真实名：局部变量解析回源码名（如 request），字段/方法取显示名；
   *  符号 = 实参子树里第一个"非 local"的真实符号（字段/参数/方法）——局部变量一律不暴露成
   *         opaque 的 "local N"，全是局部则留空。既修 symbol=local，也避免 label 退回 "arg"。
   */
  private String[] argLabel(String file, SyntaxTree.Node arg) {
    String firstName = null;
    String best = null;
    java.util.ArrayDeque<SyntaxTree.Node> stack = new java.util.ArrayDeque<>();
    stack.push(arg);
    while (!stack.isEmpty()) {
      SyntaxTree.Node n = stack.pop();
      for (SyntaxTree.OccurrenceData occ : n.occurrences) {
        String sym = occ.symbol;
        if (sym == null || sym.isEmpty()) continue;
        if (ScipSymbols.isLocal(sym)) {
          if (firstName == null) {
            Map<String, String> m = localNamesByFile.get(file);
            String rn = m == null ? null : m.get(sym);
            firstName = (rn != null && !rn.isEmpty()) ? rn : shortName(sym);
          }
        } else {
          if (best == null) best = sym;
          if (firstName == null) {
            SymbolInformation info = symbols.get(sym);
            firstName = info != null && !info.getDisplayName().isEmpty() ? info.getDisplayName() : shortName(sym);
          }
        }
      }
      for (SyntaxTree.Node c : n.children) stack.push(c);
    }
    return new String[] {firstName != null ? firstName : "", best != null ? best : ""};
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

  /** Look up a symbol's {@link SymbolInformation}. 局部符号（"local N"）每文件各自编号、跨文件
   *  会重号，须按 (文件相对路径, 符号) 区分；非局部符号全局唯一，直接按符号查。 */
  private SymbolInformation infoOf(String file, String symbol) {
    if (ScipSymbols.isLocal(symbol)) return symbols.get(file + " " + symbol);
    return symbols.get(symbol);
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

  private static int rangeCol(SyntaxTree.OccurrenceData occ) {
    return occ.range == null ? 0 : occ.range.startCharacter();
  }

  private static int rangeCol(SyntaxTree.Node node) {
    return node.range == null ? 0 : node.range.startCharacter();
  }

  private static int rangeColEnd(SyntaxTree.OccurrenceData occ) {
    return occ.range == null ? 0 : occ.range.endCharacter();
  }

  private static int rangeColEnd(SyntaxTree.Node node) {
    return node.range == null ? 0 : node.range.endCharacter();
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

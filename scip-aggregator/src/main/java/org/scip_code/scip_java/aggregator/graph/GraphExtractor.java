package org.scip_code.scip_java.aggregator.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.NodeKind;
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
  /** 文件相对路径 -> 源码文本，供字面量节点按 range 取真实文本。 */
  private final Map<String, String> sources;

  private String literalText(String file, ScipRange range) {
    if (range == null) return "";
    String src = sources.get(file);
    if (src == null) return "";
    // SCIP range 的 startCharacter/endCharacter 是"行内列偏移"；按行定位再取列区间。
    int sl = range.startLine();
    String[] lines = src.split("\n", -1);
    if (sl < 0 || sl >= lines.length) return "";
    String ln = lines[sl];
    int s = range.startCharacter(), e = range.endCharacter();
    if (s < 0 || e > ln.length() || s > e) return "";
    String t = ln.substring(s, e).trim();
    return t.isEmpty() ? ln.trim() : t;
  }

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

  // 每个方法压一个"隐式体块"承载其方法体内的链（见 pushMethodScope），与 methodSymbols 平行。
  // 不再物化 kind=METHOD 的"根条件"假节点、不建 ROOT 边：那个节点没有自己的语义（无守卫值、
  // 不参与 CONTROLS/逻辑维度、位置还是借方法的 range）。方法体链首改为 Method 节点自身——
  // 体块的 startFrom = Method，于是方法体首事件经 "Method -[:NEXT]-> 首事件" 进入（跨函数展开
  // 只剩 CALLS→NEXT 两跳）。
  //
  // 为什么必须有这个块：Kotlin 表达式体方法（`override fun f() = if(…) {…}`）**没有体 BLOCK**，
  // 先前它的表达式内容落在 blockStack 为空的状态里 → 守卫读/分支全成孤立节点，而老代码用在
  // "深度 1"块里塞根条件的办法硬接，导致分支首事件被接成 `IF -[:NEXT]-> 方法链首` 的反向边
  // （okhttp 实测 88 条这类回边）。隐式块让表达式体方法与块体方法走同一条路径：
  // 方法有真体 BLOCK 时由该 BLOCK 接管（见 enterBlock，并置 methodBodyTakenOver = true），
  // 没有时隐式块一直承载到方法出口——出口靠**对象同一性**认领，不能只看栈顶（嵌套方法会把
  // 外层的隐式块误当自己的）。
  private final Deque<BlockBuilder> implicitBodies = new ArrayDeque<>();
  private final Deque<Boolean> methodBodyTakenOver = new ArrayDeque<>();
  private final Deque<String> conds = new ArrayDeque<>();
  private final Deque<String> methodSymbols = new ArrayDeque<>();
  // 独立方法单元：被调方为 `local N` 的方法（匿名对象成员/lambda/局部函数）不应把方法体并进
  // 外层函数的 NEXT 执行链。isolatedStack 与 methodSymbols 平行；isolatedBodies 是其体链记录器，
  // 出口不回并父块。
  private final Deque<Boolean> isolatedStack = new ArrayDeque<>();
  private final java.util.Set<BlockBuilder> isolatedBodies = new java.util.HashSet<>();
  // switch case 体首事件捕获：非负时，blockStack 深度 == 该值+1 的块首事件记入 capturedBodyHead
  // （供 fall-through 连边）；-1 表示不捕获。label 一并记下，连边时才能给出正确的 from 标签。
  private int captureBodyHeadDepth = -1;
  private String capturedBodyHead = null;
  private String capturedBodyHeadLabel = null;
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
  // Local-symbol 调用点的标签后置补正：enterInvocation 时 localNamesByFile 可能还没处理到该 local 的声明
  // （Kotlin 匿名对象/lambda/局部函数的被调方只有 `local N` 符号），全文件跑完后再把 `local N` 换成它的
  // 源码局部名，避免 CALLED_METHOD 标签显示成裸数字 `N`。存 (callId, file, localSymbol)。
  private final java.util.List<String[]> pendingLocalCallNames = new java.util.ArrayList<>();
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
  /**
   * abrupt 出口槽（return/throw）的运行时 id。这些节点是链的<b>末端</b>：控制流在此离开，
   * 故任何 NEXT 都不得<b>从</b>它们出发（`return/throw → 后续代码` 恒为假边）。
   * 用全局集合而非块级标记，因槽可能经多条路径被后续事件续接——块尾汇合、延迟局部写冲排、
   * 分支 join 等；只要在唯一的 NEXT 出口处统一拦截，即可覆盖全部路径。
   * 入边不受影响（…→x 读→RETURN 仍正常）。
   */
  private final java.util.Set<String> abruptSlotIds = new java.util.HashSet<>();
  // 赋值/声明的"写"延迟到 RHS 求值后再入链：`x = <跨行 RHS>`(如 when/if 表达式)的写要等整个 RHS
  // 求值完才能作为"写"入链，否则会把写排到 RHS 读之前(先写后读)。记录每个写对应的 flush 行 =
  // 赋值语句末行，emitReferenceValues 用它冲排(缺省回退 LHS 行)。
  private final java.util.Map<String, Integer> writeFlushLines = new java.util.HashMap<>();

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
    final Map<String, Object> props; // 汇入边属性（如无 else if 的 fall-through=branch:false、loop 退出=false）

    Join(String id, String label) {
      this(id, label, null);
    }

    Join(String id, String label, Map<String, Object> props) {
      this.id = id;
      this.label = label;
      this.props = props;
    }
  }

  /** A runtime event in an order chain, with its node label, plus optional entry-edge props
   *  (e.g. {@code branch:"true"/"false"} for if/when 分支入口，{@code exception:<全名>} for catch 入口)。 */
  private static final class EventRef {
    final String id;
    final String label;
    final Map<String, Object> props;

    EventRef(String id, String label) {
      this(id, label, null);
    }

    EventRef(String id, String label, Map<String, Object> props) {
      this.id = id;
      this.label = label;
      this.props = props;
    }
  }

  /** Builds one block's event-level order chain. */
  private static final class BlockBuilder {
    final List<EventRef> events = new ArrayList<>();
    final List<Join> pendingJoins = new ArrayList<>();
    EventRef startFrom = null; // event this block continues from (enclosing chain's last event)
    /**
     * 本块是否已由 abrupt 语句（return/throw）终止。终止后块内后续事件不可达，
     * 且本块的链尾不得作为"续接末端"泄漏到父块的下一个事件——否则会连出
     * "return/throw → 之后不可达的代码"这种假边。
     */
    /** 本块链尾若为 abrupt 槽（return/throw），不得作为续接末端泄漏给父块（见 finish()）。 */
    boolean abruptTail = false;

    EventRef lastEvent() {
      return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    List<Join> finish() {
      List<Join> ends = new ArrayList<>(pendingJoins);
      // 仅当还没有任何待合并末端时才把 lastEvent 当作末端：若本块以"尚未被后续事件消耗的
      // 分支合并点(pendingJoin)"结束(即 fork 是本块最后一条语句)，这些 pendingJoin 才是本块
      // 真正的续接末端；此时 lastEvent 往往是被 fork 后遗留的分叉条件自身，不应作为末端泄漏到
      // 父块的下一个事件(否则有 else 兜底的 if 会额外连一条"条件→下一事件")。无 else 的 if
      // 由 walkConditionChildren 的 cond-as-join 把条件也放入 pendingJoin，其 fall-through 不受影响。
      // 链尾是 abrupt 槽（return/throw）时，控制流已离开本块，不续接父块下一事件。
      // 这使"分支在此结束"成立：不会连出 `return/throw → 之后不可达的代码`。
      if (ends.isEmpty() && abruptTail) {
        pendingJoins.clear();
        return ends;
      }
      if (ends.isEmpty()) {
        EventRef last = lastEvent();
        if (last != null) {
          // 条件是块末(如无 else if 的 fall-through 终端)时，其续接父块下一事件的边即假路径
          // (条件不真、跳过分支才走到父块下一个事件)，标 branch="false"。
          ends.add(
              GraphModel.LABEL_CONDITION.equals(last.label)
                  ? new Join(last.id, last.label, Map.of("branch", "false"))
                  : new Join(last.id, last.label));
        }
      }
      pendingJoins.clear();
      return ends;
    }
  }

  private final Deque<BlockBuilder> blockStack = new ArrayDeque<>();
  // 进入分支时"该分支起点"（当前条件 / kind=ELSE 条件）。用栈而非单值：嵌套分支/条件下每一层
  // 有独立快照，避免被递归改写（原全局 lastConditionEvent 曾被 then 分支里的嵌套条件覆盖，
  // 导致 else 分支的 ELSE 边错位——见 walkConditionChildren）。本条件自身 id 由 enterCondition 压入
  // conds 作用域栈，不再依赖独立全局。
  private final Deque<EventRef> pendingBranchStartFrom = new ArrayDeque<>();
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
  // RETURN 的链入延迟到其子节点(返回值)读取之后(在 exit 时才入链)：`return x` 先读 x 再返回，故
  // 链序应为 …→x 读→RETURN，而非 RETUREN→x(当前 enter 时即入链导致 NEXT 方向反了)。
  private final Deque<String> pendingReturnChains = new ArrayDeque<>();
  // THROW 同 RETURN：延迟到抛出表达式读取之后入链（`throw new E()` → …→E 读→THROW）。
  private final Deque<String> pendingThrowChains = new ArrayDeque<>();
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

  /** 冲掉"写行 <= upto"的延迟写(含等号)：块退出时用块末行，使属于本块的写入链、跨多行 RHS 的写保留。 */
  private void flushLocalWritesUpTo(String file, int upto) {
    for (int i = 0; i < pendingLocalWrites.size(); ) {
      LocalWrite w = pendingLocalWrites.get(i);
      if (w.line <= upto) { pendingLocalWrites.remove(i); appendChainEvent(file, w.id, w.label, w.line); }
      else i++;
    }
  }

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
    // 本块已因 abrupt 语句终止：其后同块事件不可达，不再续接。
    // 槽自身入链时 abruptTail 尚为 false（标记发生在该子语句 walk 完之后），故它仍正常入链成为链尾。
    if (b.abruptTail) return;
    boolean hasJoins = !b.pendingJoins.isEmpty();
    for (Join j : b.pendingJoins) {
      // abrupt 槽不作为任何 NEXT 的起点（控制流已离开）；见 abruptSlotIds。
      if (abruptSlotIds.contains(j.id)) continue;
      writer.addEdge(GraphModel.REL_NEXT, j.label, j.id, label, id, j.props);
    }
    b.pendingJoins.clear();
    EventRef prev = b.lastEvent();
    if (prev != null) {
      if (!hasJoins && !prev.id.equals(id) && !abruptSlotIds.contains(prev.id)) {
        writer.addEdge(GraphModel.REL_NEXT, prev.label, prev.id, label, id);
      }
    } else if (b.startFrom != null) {
      // 分支块首事件都从条件经 NEXT(顺序/时机)进入——then 从 IF、else 从 ELSE节点。
      // 这样两条分支的首事件都挂在顺序链上；ELSE 边只作"条件 --ELSE--> else节点"的逻辑标记。
      // 分支入口边带 startFrom 的 props，如 if 的 branch=true/false、catch 的 exception=<全名>。
      writer.addEdge(GraphModel.REL_NEXT, b.startFrom.label, b.startFrom.id, label, id, b.startFrom.props);
      b.startFrom = null;
    }
    b.events.add(new EventRef(id, label));
    // switch case 体首事件捕获：fall-through 的体尾要连到下一 case 体首；仅捕获合成体块的最外层首事件
    // （blockStack 深度 = 进入体块前的深度 +1，且是该块的首事件）。
    if (captureBodyHeadDepth >= 0 && blockStack.size() == captureBodyHeadDepth + 1
        && b.events.size() == 1) {
      capturedBodyHead = id;
      capturedBodyHeadLabel = label;
    }
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
    this(writer, project, symbols, java.util.Collections.emptyMap());
  }

  public GraphExtractor(
      GraphSink writer,
      String project,
      Map<String, SymbolInformation> symbols,
      Map<String, String> sources) {
    this.writer = writer;
    this.project = project;
    this.symbols = symbols;
    this.sources = sources;
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

  /** 由形参名合成 SCIP 形参符号：`方法().(参数名)`（外部方法无独立形参 SymbolInformation 时用）。
   *  方法符号形如 `…/foo().` 或 `…/foo(+13).`（重载位置消歧，也以 `.` 结尾），都可直接拼 `. (参数名)`；
   *  之前只认 `().` 结尾，导致重载方法槽的 symbol 落空。 */
  static String paramSymbolFor(String calleeSymbol, String paramName) {
    if (calleeSymbol == null || paramName == null || paramName.isEmpty()) return null;
    if (calleeSymbol.endsWith(".")) return calleeSymbol + "(" + paramName + ")";
    return null;
  }

  /** 清理形参名：scip-java 对 Kotlin 合成/内联函数（`apply`/`also`/`use` 等）的形参显示名会把注解文本
   *  `) @InlineOnly() @JvmName(...) …` 漏进来（这类字符串里并无真正参数名），回退成 `#argIndex` 保持一致，
   *  不吐注解垃圾。正常名（如 `HttpUrl arg0`、`vararg elements` 的空白兜底）原样保留。 */
  static String cleanParamName(String raw, int argIndex) {
    if (raw == null) return "#" + argIndex;
    String s = raw.trim();
    if (s.isEmpty()) return "#" + argIndex;
    if (s.contains("@") || s.contains(") ")) return "#" + argIndex;
    return s;
  }

  private static boolean isPlainName(String s) {
    if (s == null || s.isEmpty()) return false;
    boolean anyLetter = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isLetter(c)) anyLetter = true;
      if (!(Character.isLetterOrDigit(c) || c == '_' || c == '`' || c == '$')) return false;
    }
    return anyLetter;
  }

  /** 全部文件提取完后，把 CALLED_PARAM 槽的符号/名字后置补正为被调函数对应形参（paramsByMethod 此时已完整）。 */
  private void fixupArgSlots() {
    for (ArgSlotFixup f : pendingArgFixups) {
      java.util.List<String> pSyms = paramsByMethod.get(f.calleeSymbol);
      java.util.List<String> pNames = paramNamesOf(f.calleeSymbol);
      if (pNames == null || f.argIndex >= pNames.size()) continue;
      String pName = cleanParamName(pNames.get(f.argIndex), f.argIndex);
      String pSym = (pSyms != null && f.argIndex < pSyms.size()) ? pSyms.get(f.argIndex) : null;
      if (pSym == null || pSym.isEmpty()) pSym = paramSymbolFor(f.calleeSymbol, pName);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", pName);
      if (pSym != null) m.put("symbol", pSym);
      writer.addNode(GraphModel.LABEL_VALUE, f.id, m); // MERGE by id → 更新该槽的 name/symbol
    }
    pendingArgFixups.clear();
  }

  /** 全文件跑完后，把 `local N` 调用点的标签换成其源码局部名（声明的处理可能晚于调用点）。 */
  private void fixupLocalCallNames() {
    for (String[] f : pendingLocalCallNames) {
      Map<String, String> m = localNamesByFile.get(f[1]);
      String sn = m == null ? null : m.get(f[2]);
      if (sn == null || sn.isEmpty()) continue;
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("name", sn);
      writer.addNode(GraphModel.LABEL_CALLED_METHOD, f[0], p); // MERGE by id → 更新标签
    }
    pendingLocalCallNames.clear();
  }

  public void emitRelationships() {
    fixupArgSlots();
    fixupLocalCallNames();
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
   * Cross-function order (called at the end of {@link #emitRelationships()}).
   *
   * <p><b>不再建立跨函数的 NEXT 边</b>：NEXT(时序)只表达<b>单个方法体内</b>的事件先后。
   * 跨函数关系由 {@code CALLS}/{@code RET_OF}/{@code ARG_OF} 等逻辑边表达，但不再用 NEXT 把
   * 调用点接到被调方法首事件、或被调方法退出接到调用返回。这样 NEXT 从某方法 {@code ROOT} 条件
   * 沿可达走时只会留在该函数体内，不会被 NEXT 漏进同文件/其他文件的方法——'按函数限域'才干净。
   */
  private void emitOrderRelationships() {
    // intentionally no-op: cross-function NEXT is disabled (see method javadoc).
  }

  // ---------------------------------------------------------------------------
  // Tree walk
  // ---------------------------------------------------------------------------

  private void walk(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    if ("TRY".equals(node.kind)) {
      handleTryCatch(file, node, parent, index);
      return;
    }
    if ("WHEN".equals(node.kind)) {
      handleWhen(file, node, parent, index);
      return;
    }
    if (isSwitchKind(node.kind)) {
      handleSwitch(file, node, parent, index);
      return;
    }
    enter(file, node, parent, index);
    if (isConditionKind(node.kind)) {
      walkConditionChildren(file, node);
    } else {
      List<SyntaxTree.Node> children = node.children;
      boolean seq = isStatementSequence(node.kind);
      for (int i = 0; i < children.size(); i++) {
        walk(file, children.get(i), node, i);
        // 语句级终止：语句序列(块体)里一旦走过一条必然 abrupt 的直接子语句（return/throw 语句），
        // 其后同块语句即不可达——立刻标记本块，使后续事件不再续接（含 RETURN/THROW 槽 → 后续代码）。
        // 判定只看"直接子语句"这一层，不深入条件分支/表达式，故 `if (c) return;`（条件为假时仍继续）
        // 与 `val x = y ?: throw …`（throw 嵌在 elvis 里，该声明语句本身正常完成）都不会误判。
        if (seq && isAbruptStatementNode(children.get(i)) && !blockStack.isEmpty()) {
          blockStack.peek().abruptTail = true;
        }
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
    // 当前条件自身 id 已由 enterCondition 压入 conds 作用域栈（栈顶=本条件）。取它作分支锚点，
    // 不依赖会被嵌套条件改写的全局 lastConditionEvent——否则 else 分支的 `IF --ELSE--> ELSE` 边会
    // 挂到 then 分支里最内层条件上，导致本应带 else 的 IF 反而没有 ELSE 边（归属错位）。
    EventRef condRef = null;
    String curCond = conds.isEmpty() ? null : conds.peek();
    if (curCond != null) condRef = new EventRef(curCond, GraphModel.LABEL_CONDITION);

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
      // 每条分支(then/else/else-if)首事件都从条件经 NEXT 进入；else 不再物化 kind=ELSE 节点。
      // 分支块首事件经 enterBlock 消费 startFrom；else-if(IF 节点)走其自身 walk，守卫值先入链。
      int depthBefore = pendingBranchStartFrom.size();
      // 分支入口边带 branch 属性：if 分支0=then(true)、分支1=else(false)；循环体进入=条件为真(true)。
      pendingBranchStartFrom.push(
          condRef != null
              ? new EventRef(condRef.id, condRef.label, branchEntryProps(node, i))
              : null);
      pushBranchScope();
      walk(file, branches.get(i), node, children.indexOf(branches.get(i)));
      branchScopes.add(scopeStack.pop());
      // 恢复分支前的栈深：本分支的起点或被 enterBlock 消费(pop)、或未消费仍留在栈顶——统一弹回，
      // 保证该起点不被泄漏到下一分支 / 外层（与原来"分支后置空"等价，但各嵌套层独立）。
      while (pendingBranchStartFrom.size() > depthBefore) pendingBranchStartFrom.pop();
    }
    // 循环(while/for/do-while):条件恒 2 分叉——真路径→循环体首事件，假路径→退出(本块下一事件)。
    // 与 if 不同，循环体末端不"汇合到 next"，而是**回边到条件**，形成 NEXT 环(重复执行)；
    // 退出只能由条件变假，故 next 只从条件(假路径)汇入。为此：把 body 分支的末端(exitBlock 已放入
    // 本块 pendingJoins)定向为回边到条件，而不再线性续到 next；随后把条件自身放入 pendingJoins，
    // 使本块下一个事件从条件(退出=假路径)接入。
    if (isLoopKind(node.kind) && condRef != null && !blockStack.isEmpty()) {
      BlockBuilder parent = blockStack.peek();
      // 回边指向"条件句首事件/守卫事件"(谓词调用→其 CALLED_RETURN，否则条件首值读)，而非 LOOP 标记节点，
      // 使循环再次执行时从条件求值进入。
      String loopEventId = loopConditionEventId(file, node);
      for (Join j : parent.pendingJoins) {
        if (abruptSlotIds.contains(j.id)) continue; // 循环体以 return/throw 结尾：不回边
        if (loopEventId != null) {
          writer.addEdge(GraphModel.REL_NEXT, j.label, j.id, GraphModel.LABEL_VALUE, loopEventId);
        } else {
          writer.addEdge(GraphModel.REL_NEXT, j.label, j.id, condRef.label, condRef.id);
        }
      }
      parent.pendingJoins.clear();
      // loop 退出由条件变假，故进入 next 的汇入边即假路径，标 branch="false"。
      parent.pendingJoins.add(new Join(condRef.id, condRef.label, Map.of("branch", "false")));
    }
    // 单分支、无 else 的 if(非循环):条件为假时直落到整个 if 语句之后的下一个事件。
    // 把条件自身作为同层 pendingJoin 交到父块,使下一事件同时从"条件(假路径,跳过分支)"与
    // "分支末尾(真路径)"接入——否则该 if 只有一条"条件→分支首事件",缺了假路径的下一条。
    if (!isLoopKind(node.kind) && branches.size() == 1 && condRef != null && !blockStack.isEmpty()) {
      // 无 else 的 if：条件为假时直落到 if 之后的下一个事件，这条 fall-through 汇入边即假路径，
      // 标记 branch="false"，保证 if 的"真+假"两条 NEXT 都带 branch(不变量成立)。
      blockStack.peek().pendingJoins.add(new Join(condRef.id, condRef.label, Map.of("branch", "false")));
    }
    mergeBranchScopes(branchScopes, node);
  }

  // ---------------------------------------------------------------------------
  // try / catch / finally → 与 if/else 类似的 Condition 节点
  // ---------------------------------------------------------------------------

  /** 分支入口边带的属性：if 分支0=真路径(true)、分支1=假路径(false)；loop 进入体=条件为真(true)。 */
  private Map<String, Object> branchEntryProps(SyntaxTree.Node node, int branchIndex) {
    if (node.kind.equals("IF")) {
      return Map.of("branch", branchIndex == 0 ? "true" : "false");
    }
    if (isLoopKind(node.kind)) return Map.of("branch", "true");
    return null;
  }

  /** 建一个 Condition 节点（kind 为该条件种类）。 */
  private void addCondNode(String file, String id, String kind, SyntaxTree.Node node) {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", kind);
    writer.addNode(GraphModel.LABEL_CONDITION, id, props);
  }

  /** 以给定条件为分支起点 walk 一个子节点（若为块则其首事件从该条件锚定），并恢复 pendingBranchStartFrom 栈深。 */
  private void walkBranchFrom(String file, SyntaxTree.Node child, SyntaxTree.Node parent, int index, String condId) {
    walkBranchFrom(file, child, parent, index, condId, null);
  }

  /** 同 {@link #walkBranchFrom}，但分支入口 NEXT 边带 entryProps（如 if 的 branch、catch 的 exception）。 */
  private void walkBranchFrom(
      String file, SyntaxTree.Node child, SyntaxTree.Node parent, int index, String condId,
      Map<String, Object> entryProps) {
    int depth = pendingBranchStartFrom.size();
    pendingBranchStartFrom.push(new EventRef(condId, GraphModel.LABEL_CONDITION, entryProps));
    walk(file, child, parent, index);
    while (pendingBranchStartFrom.size() > depth) pendingBranchStartFrom.pop();
  }

  /** 提取 catch 子句异常类型的**全限定名**（取自 catch 头部的类型 occurrence 符号，如 {@code java/io/IOException#} → {@code java.io.IOException}）。 */
  private String catchExceptionType(String file, SyntaxTree.Node cat) {
    SyntaxTree.OccurrenceData occ = catchTypeOccurrence(cat);
    if (occ == null) return null;
    // SCIP 类符号形如 <modpath>Path/Name#：取 # 前、去掉空格分隔的模块/工具前缀，把 / 换成 . 得全限定名。
    String symbol = occ.symbol;
    int hash = symbol.lastIndexOf('#');
    String path = hash >= 0 ? symbol.substring(0, hash) : symbol;
    int sp = path.lastIndexOf(' ');
    if (sp >= 0) path = path.substring(sp + 1);
    return path.replace('/', '.');
  }

  /**
   * catch 头里的异常参数定义 occurrence（`catch (e: IOException)` 的 e）。只看 catch 头、不下潜
   * 体块(BLOCK)——体里的局部定义不属于 catch 参数。Kotlin 把 catch 参数归为 IdentifierParameter、
   * javac 归为 IdentifierLocal，两者都接受；只取role=definition 的 occurrence。
   */
  private static SyntaxTree.OccurrenceData localDefinitionIn(SyntaxTree.Node cat) {
    for (SyntaxTree.OccurrenceData occ : cat.occurrences) {
      if (occ.role == 1 && occ.symbol != null && !occ.symbol.isEmpty()) return occ;
    }
    for (SyntaxTree.Node c : cat.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE") || c.kind.equals("BLOCK")) {
        continue;
      }
      SyntaxTree.OccurrenceData r = localDefinitionIn(c);
      if (r != null) return r;
    }
    return null;
  }

  private SyntaxTree.OccurrenceData catchTypeOccurrence(SyntaxTree.Node cat) {
    SyntaxTree.OccurrenceData occ = findTypeOccurrence(cat);
    if (occ != null) return occ;
    // 回退：catch 头(排除体块)里第一个非 local 的 role0 occurrence（就是异常类型，如 IOException）。
    return findNonLocalOccurrence(cat);
  }

  private SyntaxTree.OccurrenceData findNonLocalOccurrence(SyntaxTree.Node n) {
    for (SyntaxTree.OccurrenceData occ : n.occurrences) {
      if (occ.role == 0 && occ.symbol != null && !occ.symbol.isEmpty()
          && !ScipSymbols.isLocal(occ.symbol)) {
        return occ;
      }
    }
    for (SyntaxTree.Node c : n.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE") || c.kind.equals("BLOCK")) continue;
      SyntaxTree.OccurrenceData r = findNonLocalOccurrence(c);
      if (r != null) return r;
    }
    return null;
  }

  /** 递归在 catch 头(排除体块)里找类型引用 occurrence（异常类型名）。大小写不敏感匹配 Type/TYPE。 */
  private SyntaxTree.OccurrenceData findTypeOccurrence(SyntaxTree.Node n) {
    for (SyntaxTree.OccurrenceData occ : n.occurrences) {
      if (occ.syntaxKind != null && occ.syntaxKind.toUpperCase().contains("TYPE")) return occ;
    }
    for (SyntaxTree.Node c : n.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE") || c.kind.equals("BLOCK")) continue;
      SyntaxTree.OccurrenceData r = findTypeOccurrence(c);
      if (r != null) return r;
    }
    return null;
  }

  /**
   * try/catch/finally（与 if/else 类似，供图上/search 呈现异常处理结构）：
   * <ul>
   *   <li>TRY 自身为 kind=TRY 条件节点，try 体从它锚定（正常完成 → finally/next）；
   *   <li>每个 catch 体**从 TRY 直接经 NEXT 扇出进入**，且该 NEXT 边带
   *       {@code exception=<异常类型全名>} 属性——不再物化 kind=CATCH 节点、无 ELSE 边；
   *   <li>FINALLY 仍为 kind=FINALLY 公共汇合节点，try 正常尾 + 各 catch 体尾汇入它，finally 体末 → next。
   * </ul>
   */
  private void handleTryCatch(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    String tryId = runtimeId(project, file, node.range, null);
    addCondNode(file, tryId, GraphModel.CONDITION_KIND_TRY, node);
    conds.push(tryId);
    try {
      // TRY 入时序链：前一事件 → TRY。
      appendChainEvent(file, tryId, GraphModel.LABEL_CONDITION, rangeLine(node));
      // 收集 try 体 / catch 链 / finally
      SyntaxTree.Node tryBody = null;
      List<SyntaxTree.Node> catchNodes = new ArrayList<>();
      SyntaxTree.Node finNode = null;
      for (int i = 0; i < node.children.size(); i++) {
        SyntaxTree.Node c = node.children.get(i);
        if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE")) continue;
        if ("CATCH".equals(c.kind)) catchNodes.add(c);
        else if ("FINALLY".equals(c.kind)) finNode = c;
        else tryBody = c;
      }
      // 1) try 体从 TRY 进入；其正常链尾留在父块 pendingJoins → 汇入 finally/next（正常完成）。
      //    注意：try 体以嵌套 if/循环结尾时，其末端 = 嵌套分支尾巴（finish() 已去掉条件锚点），
      //    与"if 块最后一条语句是 if"同理，天然正确。
      if (tryBody != null) {
        walkBranchFrom(file, tryBody, node, node.children.indexOf(tryBody), tryId);
      }
      // 2) 异常路径：不再物化 kind=CATCH 条件节点/ELSE 边。每个 catch 体从 TRY 直接经 NEXT 扇出进入，
      //    且该 NEXT 边带 exception=<异常类型全名> 属性（区分是哪种异常被抓）。catch 体尾汇入父块 pendingJoins。
      for (SyntaxTree.Node cat : catchNodes) {
        String exceptionType = catchExceptionType(file, cat);
        Map<String, Object> entryProps =
            exceptionType != null ? Map.of("exception", exceptionType) : null;
        // 异常参数（`catch (e: IOException)` 的 e）只登记声明、不 walk：walk 会把它的读写成
        // 顺序事件 append 到父块、把 try 体末的 pendingJoin 合并点消费掉。但完全不登记则
        // 该 local 符号没有声明节点、localNamesByFile 也无从得知源码名，其读节点只能退回裸数字。
        // asParameter=true：按「参数绑定」建节点（kind=PARAM、不入 NEXT 链）。若按变量声明处理，
        // 它会被当成一次延迟写入链，导致 try 体尾多出一条 NEXT 指向它（把参数绑定误作赋值）。
        SyntaxTree.OccurrenceData paramDef = localDefinitionIn(cat);
        if (paramDef != null) createDeclaration(file, cat, paramDef, cat, true);
        // catch 体从 TRY 锚定（走 startFrom 边，带 exception 属性）。只 walk 体块(BLOCK)：
        // 异常参数若按顺序事件 walk 会 appendChainEvent 到父块、把 try 体末的 pendingJoin 合并点消费掉。
        for (SyntaxTree.Node cc : cat.children) {
          if (cc.kind == null || cc.kind.isEmpty() || cc.kind.equals("WHITE_SPACE")) continue;
          if ("BLOCK".equals(cc.kind)) {
            walkBranchFrom(file, cc, cat, cat.children.indexOf(cc), tryId, entryProps);
          }
        }
      }
      // 3) finally：入链（从父块 pendingJoins 汇聚 try 正常尾 + 各 catch 体尾），作为公共汇合；finally 体末 → next。
      if (finNode != null) {
        String finId = runtimeId(project, file, finNode.range, null);
        addCondNode(file, finId, GraphModel.CONDITION_KIND_FINALLY, finNode);
        appendChainEvent(file, finId, GraphModel.LABEL_CONDITION, rangeLine(finNode));
        conds.push(finId);
        try {
          walkBranchFrom(file, finNode, node, node.children.indexOf(finNode), finId);
        } finally {
          conds.pop();
        }
      }
    } finally {
      conds.pop();
    }
  }

  /**
   * Kotlin `when {}`（含 `x = when{…}` 表达式）按 if-else-if 建模：每个非 else 的 WHEN_ENTRY 守卫
   * 物化为一个 kind=IF 条件节点，守卫读先入链，条件恒 2 分叉（真→本分支体首、假→下一个守卫/else），
   * 所有落到底的分支体尾汇入合并点（= when 之后的下一事件；若是 `x = when{…}` 则先读后写汇入 `x` 的写）。
   *
   * 之前把整个 WHEN 当 kind=IF 条件、把每个 WHEN_ENTRY 都当一条独立分支（N 叉），且把守卫读当成分支
   * 目标（常形成恒 3/N 分叉）；本实现改为逐守卫建链，与 if/else-if 的"恒 2"一致。
   */
  private void handleWhen(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    List<SyntaxTree.Node> entries = branchChildren(node);
    // 1) 头部/主语（非 WHEN_ENTRY 子节点，如 `when (subject)` 的 subject、括号）照常 walk，主语读先入链。
    for (int i = 0; i < node.children.size(); i++) {
      SyntaxTree.Node child = node.children.get(i);
      if (!entries.contains(child)) walk(file, child, node, i);
    }

    // 2) 逐非 else 守卫建条件节点并链成 if-else-if（守卫读 → 条件；条件假路径 → 下一守卫/条件）。
    List<Scope> branchScopes = new ArrayList<>();
    List<SyntaxTree.Node> condEntries = new ArrayList<>();
    List<String> condIds = new ArrayList<>();
    EventRef prevCond = null;
    for (SyntaxTree.Node entry : entries) {
      if (isElseEntry(entry)) continue;
      List<SyntaxTree.Node> guards = whenGuards(entry);
      if (guards.isEmpty()) continue;
      SyntaxTree.Node guard = guards.get(0);
      String condId = runtimeId(project, file, guard.range, null);
      addCondNode(file, condId, GraphModel.CONDITION_KIND_IF, guard);
      conds.push(condId);
      try {
        for (SyntaxTree.Node g : guards) walk(file, g, entry, entry.children.indexOf(g));
        appendChainEvent(file, condId, GraphModel.LABEL_CONDITION, rangeLine(guard));
        for (SyntaxTree.Node g : guards) {
          List<String> vids = new ArrayList<>();
          collectValueIds(file, g, vids);
          for (String vid : vids) {
            writer.addEdge(GraphModel.REL_CONTROLS, GraphModel.LABEL_VALUE, vid, GraphModel.LABEL_CONDITION, condId);
          }
        }
        if (prevCond != null) {
          // 前一守卫未匹配(假)才落到下一个守卫，此链边标 branch="false"。
          writer.addEdge(GraphModel.REL_NEXT, prevCond.label, prevCond.id, GraphModel.LABEL_CONDITION, condId,
              Map.of("branch", "false"));
        }
        prevCond = new EventRef(condId, GraphModel.LABEL_CONDITION);
      } finally {
        conds.pop();
      }
      condEntries.add(entry);
      condIds.add(condId);
    }

    // 3) 走每条分支体（真路径从各自条件进入）；合并分支作用域。
    for (int i = 0; i < condEntries.size(); i++) {
      SyntaxTree.Node body = whenBody(condEntries.get(i));
      if (body == null) continue;
      pushBranchScope();
      int depth = pendingBranchStartFrom.size();
      // 守卫匹配(真) → 本分支体，分支入口边带 branch="true"。
      pendingBranchStartFrom.push(
          new EventRef(condIds.get(i), GraphModel.LABEL_CONDITION, Map.of("branch", "true")));
      walk(file, body, condEntries.get(i), condEntries.get(i).children.indexOf(body));
      while (pendingBranchStartFrom.size() > depth) pendingBranchStartFrom.pop();
      branchScopes.add(scopeStack.pop());
    }

    // 4) else 体从最后一个条件的假路径进入；无 else 时该条件作为 fall-through 终端汇入 when 之后。
    SyntaxTree.Node elseEntry = null;
    for (SyntaxTree.Node entry : entries) {
      if (isElseEntry(entry)) { elseEntry = entry; break; }
    }
    SyntaxTree.Node elseBody = elseEntry == null ? null : whenBody(elseEntry);
    if (elseBody != null && prevCond != null) {
      pushBranchScope();
      int depth = pendingBranchStartFrom.size();
      // 全部守卫都不匹配(假) → else 体，分支入口边带 branch="false"。
      pendingBranchStartFrom.push(
          new EventRef(prevCond.id, prevCond.label, Map.of("branch", "false")));
      walk(file, elseBody, elseEntry, elseEntry.children.indexOf(elseBody));
      while (pendingBranchStartFrom.size() > depth) pendingBranchStartFrom.pop();
      branchScopes.add(scopeStack.pop());
    } else if (prevCond != null && !blockStack.isEmpty()) {
      // when 无 else：全部守卫不匹配则落入 when 之后，fall-through 汇入边即假路径，标 branch="false"。
      blockStack.peek().pendingJoins.add(new Join(prevCond.id, prevCond.label, Map.of("branch", "false")));
    }
    mergeBranchScopes(branchScopes, node);
  }

  /** 该 WHEN_ENTRY 是否为 `else ->` 兜底分支（以 else 关键字而非条件开头）。 */
  private static boolean isElseEntry(SyntaxTree.Node entry) {
    for (SyntaxTree.Node c : entry.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE")) continue;
      return c.kind.equals("else");
    }
    return false;
  }

  /** WHEN_ENTRY 的守卫表达式子节点（`g1`/`is X`/`in a..b`；都在 ARROW 之前；else 无守卫）。 */
  private static List<SyntaxTree.Node> whenGuards(SyntaxTree.Node entry) {
    List<SyntaxTree.Node> guards = new ArrayList<>();
    for (SyntaxTree.Node c : entry.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE")) continue;
      if (c.kind.equals("else")) continue;
      if (c.kind.equals("ARROW")) break; // 守卫条件都在 ARROW 之前
      guards.add(c);
    }
    return guards;
  }

  /** WHEN_ENTRY 的分支体（ARROW 之后的块/表达式）。 */
  private static SyntaxTree.Node whenBody(SyntaxTree.Node entry) {
    SyntaxTree.Node body = null;
    boolean afterArrow = false;
    for (SyntaxTree.Node c : entry.children) {
      if (c.kind == null || c.kind.isEmpty() || c.kind.equals("WHITE_SPACE")) continue;
      if (c.kind.equals("ARROW")) { afterArrow = true; continue; }
      if (afterArrow) body = c;
    }
    return body;
  }

  // ---------------------------------------------------------------------------
  // Java switch → 与 Kotlin when 对齐的分支流
  // ---------------------------------------------------------------------------

  /**
   * Java {@code switch} 建模为与 Kotlin {@code when} 对齐的分支流：选择器先入链，逐个 case 的标签
   * 表达式物化为一个 kind=IF 条件节点（标签读先入链、CONTROLS 指向该条件、假路径链到下一 case 条件），
   * 分支体从各自条件真路径进入；所有分支体尾汇入 switch 之后。default 作为兜底分支，从末条件假路径进入。
   *
   * <p>兼容新旧 javac 的两种 CASE 形状：旧版 CASE 直接含标签表达式子节点；新版包在 CASE_LABEL /
   * CONSTANT_CASE_LABEL 里。default 的 CASE 无标签表达式（仅 DEFAULT 关键字）。
   * fall-through（case 体不以 break/return/throw 结尾时坠入下一 case）在此模型里由"体尾不独立汇合、
   * 线性续到下一个 case 体首"自然表达——但为保持与 when 的恒 2 分叉语义一致，这里对每个 case 都建
   * 独立条件与分支，体尾统一汇入 switch 之后（多数 Java switch 各 case 以 break 结束，这是主路径）。
   */
  private void handleSwitch(String file, SyntaxTree.Node node, SyntaxTree.Node parent, int index) {
    List<SyntaxTree.Node> cases = switchCases(node);
    // 1) 头部（选择器表达式、括号等非 CASE/DEFAULT 子节点）照常 walk，选择器读先入链。
    for (int i = 0; i < node.children.size(); i++) {
      SyntaxTree.Node child = node.children.get(i);
      if (!isSwitchLabel(child)) walk(file, child, node, i);
    }

    // 2) 逐个非 default 的 case 建条件节点并链成 if-else-if（对齐 when：default 不建条件节点）。
    //    空 case 体（`case L1: case L2: B;` 里的 L1、`case L1: break;` 之外的裸标签）不建条件节点：
    //    没有体可承载真路径，条件会变成一个真路径无出边的悬挂节点。语义上它只是"与下一个 case 合并"，
    //    故折叠进下一个非空 case —— 即"命中本标签等价于命中下一个非空 case"，这正是合并标签的含义。
    //    condCases 因此只收非空 case，step 3/5 的数组都按 condCases 的序，彼此对齐。
    List<Scope> branchScopes = new ArrayList<>();
    List<SyntaxTree.Node> condCases = new ArrayList<>();
    List<String> condIds = new ArrayList<>();
    SyntaxTree.Node defaultCase = null;
    EventRef prevCond = null;
    for (SyntaxTree.Node c : cases) {
      if (isDefaultCase(c)) { defaultCase = c; continue; }
      // 空 case 体：不建条件节点，折叠进下一个非空 case（见上）。condCases 因此只含非空 case。
      if (caseBodyNodes(c).isEmpty()) continue;
      List<SyntaxTree.Node> labels = caseLabels(c);
      SyntaxTree.Node anchor = labels.isEmpty() ? c : labels.get(0);
      String condId = runtimeId(project, file, anchor.range, null);
      addCondNode(file, condId, GraphModel.CONDITION_KIND_IF, anchor);
      conds.push(condId);
      try {
        for (SyntaxTree.Node l : labels) walk(file, l, c, c.children.indexOf(l));
        appendChainEvent(file, condId, GraphModel.LABEL_CONDITION, rangeLine(anchor));
        for (SyntaxTree.Node l : labels) {
          List<String> vids = new ArrayList<>();
          collectValueIds(file, l, vids);
          for (String vid : vids) {
            writer.addEdge(GraphModel.REL_CONTROLS, GraphModel.LABEL_VALUE, vid,
                GraphModel.LABEL_CONDITION, condId);
          }
        }
      } finally {
        conds.pop();
      }
      if (prevCond != null) {
        // 前一 case 未匹配(假)才落到本 case，此链边标 branch="false"。
        writer.addEdge(GraphModel.REL_NEXT, prevCond.label, prevCond.id,
            GraphModel.LABEL_CONDITION, condId, Map.of("branch", "false"));
      }
      prevCond = new EventRef(condId, GraphModel.LABEL_CONDITION);
      condCases.add(c);
      condIds.add(condId);
    }

    // 3) 走每条 case 的体（非 default 真路径从各自条件进入；default 从末条件假路径进入，对齐 when else）。
    //    javac 的 CASE 子节点是 [标签, 语句..., BREAK] 平铺；包一层合成 BLOCK，使分支体经 enterBlock/
    //    exitBlock 像 when 的块体一样把链尾登记为 pendingJoin。分支体首事件单独捕获，供 fall-through 连边。
    int n = condCases.size();
    String[] bodyHeads = new String[n];
    String[] bodyHeadLabels = new String[n]; // 体首事件的节点标签，连 fall-through 边时要给出正确的 from 标签
    boolean[] fallsThrough = new boolean[n];
    List<List<Join>> caseTails = new ArrayList<>(); // 每个 case 各自体尾（fall-through 只连自己这条）
    for (int i = 0; i < n; i++) {
      SyntaxTree.Node c = condCases.get(i);
      List<SyntaxTree.Node> bodyNodes = caseBodyNodes(c);
      pushBranchScope();
      int depth = pendingBranchStartFrom.size();
      pendingBranchStartFrom.push(new EventRef(condIds.get(i), GraphModel.LABEL_CONDITION,
          Map.of("branch", "true")));
      capturedBodyHead = null;
      capturedBodyHeadLabel = null;
      captureBodyHeadDepth = blockStack.size();
      int before = blockStack.peek().pendingJoins.size();
      walk(file, syntheticBlock(c, bodyNodes), c, 0);
      captureBodyHeadDepth = -1;
      bodyHeads[i] = capturedBodyHead;
      bodyHeadLabels[i] = capturedBodyHeadLabel;
      // 本 case 体尾（本块新增的 pendingJoin）单独留存，供 fall-through 只连自己这一条。
      // 体为空时 tail 为空列表（不是不追加）——caseTails 必须与 condCases 同长同序，否则按下标取会错位。
      List<Join> tail = new ArrayList<>(blockStack.peek().pendingJoins.subList(
          before, blockStack.peek().pendingJoins.size()));
      caseTails.add(tail);
      while (pendingBranchStartFrom.size() > depth) pendingBranchStartFrom.pop();
      branchScopes.add(scopeStack.pop());
      fallsThrough[i] = bodyFallsThrough(bodyNodes);
    }

    // 4) default 体从末条件假路径进入（对齐 when 的 else）；无 default 时末条件假路径 fall-through
    //    汇入 switch 之后（"全部不匹配"的出口）。
    List<SyntaxTree.Node> defaultBody = defaultCase == null
        ? java.util.Collections.emptyList() : caseBodyNodes(defaultCase);
    String defaultHead = null;
    String defaultHeadLabel = null;
    if (!defaultBody.isEmpty() && prevCond != null) {
      pushBranchScope();
      int depth = pendingBranchStartFrom.size();
      pendingBranchStartFrom.push(new EventRef(prevCond.id, prevCond.label, Map.of("branch", "false")));
      capturedBodyHead = null;
      capturedBodyHeadLabel = null;
      captureBodyHeadDepth = blockStack.size();
      walk(file, syntheticBlock(defaultCase, defaultBody), defaultCase, 0);
      captureBodyHeadDepth = -1;
      defaultHead = capturedBodyHead;
      defaultHeadLabel = capturedBodyHeadLabel;
      while (pendingBranchStartFrom.size() > depth) pendingBranchStartFrom.pop();
      branchScopes.add(scopeStack.pop());
      // default 无 break 而坠出 switch（default 是最后一个标签）→ 体尾自然汇入 switch 之后（无需处理）。
    } else if (prevCond != null && !blockStack.isEmpty()) {
      // 无 default：全部不匹配则落入 switch 之后，fall-through 汇入边即假路径，标 branch="false"。
      blockStack.peek().pendingJoins.add(
          new Join(prevCond.id, prevCond.label, Map.of("branch", "false")));
    }

    // 5) 显式 fall-through：case 体未以 break/return/throw 结束 → 该 case 自己的体尾 NEXT 到下一个
    //    case 体首（而非汇入 switch 之后）。非 fall-through（break/return 结尾）的体尾保留，照常汇入
    //    switch 之后。
    if (!blockStack.isEmpty()) {
      BlockBuilder swBlock = blockStack.peek();
      for (int i = 0; i < n; i++) {
        if (!fallsThrough[i]) continue;
        // 下一个承接体：跳过空体 case（其体在更后面的 case 里，合并标签语义），走到第一个非空体首；
        // 一个都没有则落到 default 体首。这就是 `case L1: A; case L2: case L3: C;` 里 A 应坠到 C。
        EventRef nextHead = null;
        for (int j = i + 1; j < n && nextHead == null; j++) {
          if (bodyHeads[j] != null) nextHead = new EventRef(bodyHeads[j], bodyHeadLabels[j]);
        }
        if (nextHead == null && defaultHead != null) {
          nextHead = new EventRef(defaultHead, defaultHeadLabel);
        }
        if (nextHead == null || nextHead.id == null) continue;
        List<Join> tail = caseTails.get(i);
        for (Join j : tail) {
          if (abruptSlotIds.contains(j.id)) continue; // case 体以 return/throw 结尾：不坠落
          writer.addEdge(GraphModel.REL_NEXT, j.label, j.id, nextHead.label, nextHead.id);
        }
        swBlock.pendingJoins.removeAll(tail); // 该分支尾已坠入下一 case，不再汇入 switch 之后
      }
    }
    mergeBranchScopes(branchScopes, node);
  }

  /** 用给定语句包一层合成 BLOCK（javac 的 case 体是平铺语句，需成块才能被分支汇合机制处理）。 */
  private static SyntaxTree.Node syntheticBlock(SyntaxTree.Node src, List<SyntaxTree.Node> stmts) {
    SyntaxTree.Node block = new SyntaxTree.Node("BLOCK");
    block.range = src.range;
    block.children.addAll(stmts);
    return block;
  }

  /**
   * case 体是否 fall-through（执行完坠入下一个 case）。Java 语义：体末语句<b>必然</b>离开本 case
   * （break/return/throw/continue）时不坠落，否则坠入下一 case。空体视为坠落（合并标签）。
   *
   * <p>关键是"必然"：末条为 {@code if (c) return;} 时，c 为假仍会坠入下一个 case，故仍算坠落——
   * 若按"存在 abrupt 路径"判定，这条坠落边会丢，控制流被错误地截断。
   */
  private static boolean bodyFallsThrough(List<SyntaxTree.Node> bodyNodes) {
    if (bodyNodes.isEmpty()) return true;
    SyntaxTree.Node last = bodyNodes.get(bodyNodes.size() - 1);
    return !alwaysAbrupt(last);
  }

  /**
   * 该语句是否<b>必然</b> abrupt 离开（所有路径都离开）。不进入内层循环/匿名函数（其 break/return
   * 属于它们自己，不离开本 switch）。条件语句(if/switch/when)只在<b>所有</b>分支都必然 abrupt、
   * 且无隐式坠落出口时才必然 abrupt；try 不视为必然（异常路径之外仍可能正常完成）。
   */
  private static boolean alwaysAbrupt(SyntaxTree.Node n) {
    if (n == null) return false;
    if (isAbruptKind(n.kind)) return true;
    if (isLoopKind(n.kind)) return false; // 内层循环的 break/continue 不离开 switch
    if (n.kind.equals("LAMBDA_EXPRESSION") || n.kind.equals("FUN")) return false;
    if (isConditionKind(n.kind)) {
      // if(c) return; 只有一条分支且无 else → c 为假时坠落，不必然 abrupt。
      // if(c) return; else throw; 两条分支都 abrupt 且无坠落出口 → 必然 abrupt。
      // 循环/switch 型条件不在此保证（switch 另有 handleSwitch；这里保守判为不必然）。
      if (!n.kind.equals("IF")) return false;
      List<SyntaxTree.Node> branches = branchChildren(n);
      if (branches.size() < 2) return false; // 无 else：存在坠落出口
      for (SyntaxTree.Node b : branches) {
        if (!alwaysAbrupt(b)) return false;
      }
      return true;
    }
    // 其余包装语句（EXPRESSION_STATEMENT / BLOCK / 语句序列）：
    // BLOCK 取最后一条语句判定；其它容器取"存在必然 abrupt 的后代"。
    if (n.kind.equals("BLOCK")) {
      List<SyntaxTree.Node> stmts = nonWhitespaceChildren(n);
      return !stmts.isEmpty() && alwaysAbrupt(stmts.get(stmts.size() - 1));
    }
    for (SyntaxTree.Node c : n.children) {
      if (alwaysAbrupt(c)) return true;
    }
    return false;
  }

  private static boolean isAbruptKind(String kind) {
    return kind.equals("BREAK") || kind.equals("RETURN")
        || kind.equals("THROW") || kind.equals("CONTINUE")
        || kind.equals("YIELD");
  }

  private static boolean isSwitchKind(String kind) {
    return isKind(kind, "SWITCH", "SWITCH_EXPRESSION");
  }

  /** switch 的 case/default 子节点。 */
  private static List<SyntaxTree.Node> switchCases(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    for (SyntaxTree.Node c : nonWhitespaceChildren(node)) {
      if (isSwitchLabel(c)) out.add(c);
    }
    return out;
  }

  private static boolean isSwitchLabel(SyntaxTree.Node n) {
    return n.kind.equals("CASE") || n.kind.equals("DEFAULT");
  }

  /** 该 CASE 是否 default：无标签表达式（旧 javac 的裸 CASE 里只有 DEFAULT 关键字；新 javac 直接是 DEFAULT）。 */
  private static boolean isDefaultCase(SyntaxTree.Node c) {
    if (c.kind.equals("DEFAULT")) return true;
    for (SyntaxTree.Node k : nonWhitespaceChildren(c)) {
      if (k.kind.equals("DEFAULT") || k.kind.equals("DEFAULT_CASE_LABEL")) return true;
    }
    return false;
  }

  /** case 的标签表达式：javac 的 CASE 子节点形如 [标签表达式, 语句..., BREAK]，无分隔符——
   *  只取第一个有意义子节点作标签（`case A, B:` 多标签、或新版 CASE_LABEL 包装时也成立）。 */
  private static List<SyntaxTree.Node> caseLabels(SyntaxTree.Node c) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    for (SyntaxTree.Node k : nonWhitespaceChildren(c)) {
      if (isCaseKeyword(k.kind)) continue;
      out.add(k); // 仅第一个非关键字子节点 = 标签表达式
      break;
    }
    return out;
  }

  /** case 的体语句（标签之后的语句，去掉 BREAK/CASE/DEFAULT 关键字）。default 无标签表达式，不跳过首条。 */
  private static List<SyntaxTree.Node> caseBodyNodes(SyntaxTree.Node c) {
    List<SyntaxTree.Node> kids = nonWhitespaceChildren(c);
    List<SyntaxTree.Node> out = new ArrayList<>();
    boolean skipLabel = !isDefaultCase(c); // 非 default：首个非关键字子节点是标签表达式，跳过
    for (SyntaxTree.Node k : kids) {
      if (isCaseKeyword(k.kind)) continue;
      if (skipLabel) { skipLabel = false; continue; }
      out.add(k);
    }
    return out;
  }

  private static boolean isCaseKeyword(String kind) {
    return kind.equals("DEFAULT") || kind.equals("DEFAULT_CASE_LABEL")
        || kind.equals("CASE") || kind.equals("CASE_LABEL")
        || kind.equals("CONSTANT_CASE_LABEL");
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
      // 方法体块 = 直接挂在方法节点下的 BLOCK。据此认领方法链首（Method）——不能按栈深/栈顶判定：
      // 前者会被表达式体方法的分支块误判，后者会被嵌套方法抢走外层的体块。
      enterBlock(node, file, parent != null && isMethodKind(parent.kind));
    }

    SyntaxTree.OccurrenceData def = definition(node);
    if (def != null) createDeclaration(file, node, def, parent);

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
    if (isThrowKind(node.kind)) {
      handleThrow(file, node);
    }

    emitReferenceValues(file, node);
    emitLiteralIfAny(file, node); // 字面量是树里的节点(非 occurrence)，单独建 LITERAL 节点
  }

  /** 精确判断：该节点 kind 是否是可打印的字面量（数字/布尔/null/字符串整串等），排除模板切片。 */
  private static boolean isLiteralNodeKind(String k) {
    if (k == null) return false;
    k = NodeKind.canonical(k); // Kotlin INTEGER_CONSTANT/STRING_TEMPLATE/NULL 等归一到字面量规范名
    if (k.endsWith("CONSTANT")) return true; // 未映射的其它 *CONSTANT 字面量
    switch (k) {
      case "NULL_LITERAL", "INT_LITERAL", "LONG_LITERAL", "FLOAT_LITERAL", "DOUBLE_LITERAL",
           "BOOLEAN_LITERAL", "CHAR_LITERAL", "STRING_LITERAL", "OBJECT_LITERAL",
           "CLASS_LITERAL_EXPRESSION" -> { return true; }
      default -> {}
    }
    return false;
  }

  /** 若当前节点是字面量，为之建 LITERAL Value 节点：真实文本为名、入 NEXT 链、挂 LEADS_TO、可 CONTROLS。 */
  private void emitLiteralIfAny(String file, SyntaxTree.Node node) {
    if (node == null || node.range == null || !isLiteralNodeKind(node.kind)) return;
    String id = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_LITERAL);
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", literalText(file, node.range)); // 真实源码文本：如 "upgrade"、1300、true
    props.put("symbol", "");
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", GraphModel.VALUE_KIND_LITERAL);
    props.put("access", "read");
    writer.addNode(GraphModel.LABEL_VALUE, id, props);
    appendChainEvent(file, id, GraphModel.LABEL_VALUE, rangeLine(node)); // 顺序 NEXT
  }

  /**
   * @param isMethodBody 本 BLOCK 是否直接挂在方法节点下（即方法体块）。是则接管该方法在
   *     pushMethodScope 里压下的隐式体块：继承其链首（Method），隐式块退场。
   */
  private void enterBlock(SyntaxTree.Node node, String file, boolean isMethodBody) {
    BlockBuilder b = new BlockBuilder();
    if (isMethodBody && !implicitBodies.isEmpty() && !methodBodyTakenOver.peek()) {
      // 方法体不是"分支块"：先于 pendingBranchStartFrom 判定——即便该方法定义在某个分支里
      // （如 lambda 体内），它的体也不该从那个分支条件进入。
      BlockBuilder imb = implicitBodies.pop();
      b.startFrom = imb.startFrom;
      blockStack.pop();
      if (isolatedBodies.remove(imb)) isolatedBodies.add(b);
      methodBodyTakenOver.pop();
      methodBodyTakenOver.push(true);
    } else if (!pendingBranchStartFrom.isEmpty()) {
      // 分支块起点取栈顶（当前分支压入的），弹出使该分支后续的嵌套块不误用同一起点。
      b.startFrom = pendingBranchStartFrom.pop();
    } else if (!blockStack.isEmpty()) {
      BlockBuilder parent = blockStack.peek();
      if (!parent.pendingJoins.isEmpty()) {
        // 非分支进入块(如 try/finally 体、裸块)时，父块的 lastEvent 仍是"块前"的旧事件，但兄弟块刚
        // 结束时其链尾已作为同层 pendingJoin 插入父块。把父块的这些合并点移交到本块，使本块首事件
        // 从兄弟链尾(合并点)续接，而不是再从旧 lastEvent 上分叉(否则一个节点会向多个兄弟块各出 NEXT)。
        b.pendingJoins.addAll(parent.pendingJoins);
        parent.pendingJoins.clear();
      } else {
        EventRef prev = parent.lastEvent();
        if (prev != null) b.startFrom = prev;
        else if (isolatedBodies.contains(parent) && parent.startFrom != null) {
          // 独立方法体的首块：从该方法自身起链（不含外层函数块），保证独立方法自成一条时序。
          b.startFrom = parent.startFrom;
          parent.startFrom = null;
        }
      }
    }
    blockStack.push(b);
  }

  private void exit(String file, SyntaxTree.Node node) {
    if (isConditionKind(node.kind) && !conds.isEmpty()) conds.pop();
    if (isMethodKind(node.kind)) {
      // 方法体块（隐式体块，或已接管的真体 BLOCK）在 exitBlock 中随 BLOCK 弹出；
      // 表达式体方法没有体 BLOCK，其隐式体块要在此收尾。按**对象同一性**认领自己那一块——
      // 不能只看栈顶：嵌套方法（lambda/匿名对象）的方法体会压在外层之上，用栈顶会弹错外层的块。
      boolean takenOver = !methodBodyTakenOver.isEmpty() && methodBodyTakenOver.pop();
      if (!takenOver && !implicitBodies.isEmpty()) {
        BlockBuilder imb = implicitBodies.pop();
        if (!blockStack.isEmpty() && blockStack.peek() == imb) {
          blockStack.pop().finish();
        }
        isolatedBodies.remove(imb);
      }
      // 方法顶层不压 conds（见 pushMethodScope），故此处不弹。
      if (!scopeStack.isEmpty()) scopeStack.pop();
      if (!methodSymbols.isEmpty()) methodSymbols.pop();
      if (!isolatedStack.isEmpty()) isolatedStack.pop();
    }
    if (node.kind.equals("BLOCK")) {
      exitBlock(file, rangeEndLine(node));
    }
    // Invocation exit: commit this call's deferred chain events (args → calledMethod → calledReturn)
    // into the enclosing block, after the arg-internal reads that were chained during child walk.
    if (isInvocationKind(node.kind) && !pendingCallChains.isEmpty()) {
      for (EventRef e : pendingCallChains.pop()) {
        appendChainEvent(file, e.id, e.label, rangeLine(node));
      }
    }
    // RETURN exit: 在返回值读取之后把 RETURN 入链(…→x 读→RETURN)，修复 NEXT 方向颠倒。
    if (isReturnKind(node.kind) && !pendingReturnChains.isEmpty()) {
      appendChainEvent(file, pendingReturnChains.pop(), GraphModel.LABEL_VALUE, rangeLine(node));
    }
    // THROW 同 RETURN：在抛出表达式读取之后把 THROW 入链（…→new E() 读→THROW）。
    if (isThrowKind(node.kind) && !pendingThrowChains.isEmpty()) {
      appendChainEvent(file, pendingThrowChains.pop(), GraphModel.LABEL_VALUE, rangeLine(node));
    }
  }

  /**
   * On block exit, its chain ends (last event + unresolved branch joins) continue into the
   * enclosing block's next event (via pendingJoin) — or, for the method body, become the method's
   * cross-function exit events.
   */
  private void exitBlock(String file, int endLine) {
    // 块结束前冲掉"属于本块"的遗留延迟写(写行 <= 块末行)。用块末行而非 MAX_INT，避免把跨行 RHS
    // (如 `x = when{…}`)的写提前冲进分支子块——该写的冲排行在 RHS 末行，应等链推进到其后才入链。
    flushLocalWritesUpTo(file, endLine);
    BlockBuilder b = blockStack.pop();
    List<Join> ends = b.finish();
    if (isolatedBodies.remove(b)) {
      // 独立方法体（lambda/匿名对象成员/局部函数）：链末端不回并外层函数块（自成一条时序）。
      return;
    }
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
    // 匿名对象成员/lambda/局部函数：独立方法单元，方法体不进外层函数 NEXT 链。
    boolean isolated = def != null && ScipSymbols.isLocal(def.symbol);
    isolatedStack.push(isolated);
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
    // 方法体链首 = Method 节点自身：压一个隐式体块，其 startFrom = Method。
    // 方法若有真体 BLOCK，该 BLOCK 进来时接管它（见 enterBlock）；表达式体方法则由它承载到底。
    // 不再物化 kind=METHOD 的"根条件"假节点、不建 ROOT 边（见字段注释）。
    // conds 是"当前条件作用域"栈：方法体顶层不属于任何条件，故不压栈（栈空即"无当前条件"）。
    BlockBuilder body = new BlockBuilder();
    if (hasSymbol) {
      body.startFrom = new EventRef(declId(project, file, symbol), GraphModel.LABEL_METHOD);
    }
    blockStack.push(body);
    implicitBodies.push(body);
    methodBodyTakenOver.push(false);
    if (isolated) {
      // 独立方法体：出口不回并外层函数块（该方法是独立单元，不在外层执行序中）。
      isolatedBodies.add(body);
    }
  }

  private void createDeclaration(
      String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def, SyntaxTree.Node parent) {
    createDeclaration(file, node, def, parent, false);
  }

  /**
   * @param asParameter 该定义是「参数绑定」（如 catch 参数），而非「带初始化的变量声明」（{@code val/var x = …}）。
   *     catch 参数在 SCIP 里可能落成 IdentifierLocal（javac 的 EXCEPTION_PARAMETER、Kotlin 的 catch 头），
   *     若按变量声明处理，它会被登记成一次「延迟写」并入 NEXT 链——于是 try 体尾会连一条 NEXT 到它，
   *     等于在图上说「try 体执行完 → 写下 e」。实际 e 由异常本身写入、且在进入 catch 体时即已绑定，
   *     不属于 try 体的执行序。故按参数语义建节点（kind=PARAM、不入链）。
   */
  private void createDeclaration(
      String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def, SyntaxTree.Node parent,
      boolean asParameter) {
    String symbol = def.symbol;
    if (symbol.isEmpty()) return;
    String syntaxKind = asParameter ? "IdentifierParameter" : def.syntaxKind;
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
      if (ScipSymbols.isLocal(symbol)) {
        // 局部形参：Kotlin 的 lambda 形参 / catch 参数都归到 IdentifierParameter，但符号是
        // per-file 的 "local N"。它们同样要登记源码名，否则该符号的读节点只能退回裸数字
        // （如 `use { sink -> … }` 的 sink 全被显示成 67）。与 IdentifierLocal 同一张表。
        localNamesByFile
            .computeIfAbsent(file, k -> new java.util.HashMap<>())
            .put(symbol, name);
      } else {
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
        // 写节点延迟到 RHS 读之后再入链，保证 `val x = rhs` 先读后写。用**语句末行**作冲排边界：
        // 跨行赋值的 RHS 读在更大的行上，若按 LHS 行冲排会在第一个 RHS 读前就把写挤出(写成"先写后读")。
        // 写延迟到整个声明语句(含多行 RHS)求值完再入链，保证 `val x = <跨行 RHS>` 先读后写。
        // Kotlin 的 LHS def 挂在 IDENTIFIER 上(行号仅到 LHS)，RHS 是其兄弟——取声明节点(父)末行；
        // javac 的 def 在声明节点(如 VARIABLE)上、自身已含 initializer，用 node 末行即可。
        pendingLocalWrites.add(
            new LocalWrite(declarationEndLine(node, parent), localId, GraphModel.LABEL_VALUE));
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
      if (isWrite) pendingLocalWrites.add(new LocalWrite(writeFlushLines.getOrDefault(id, rangeLine(occ)), id, GraphModel.LABEL_VALUE));
      else appendChainEvent(file, id, GraphModel.LABEL_VALUE, rangeLine(occ));

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
    // 写延迟到整个赋值语句(RHS 含多行表达式时)求值完再入链，保证 `x = when{…}` / `x = if(…)` 先读后写，
    // 且分支尾能以该写为合并点。用赋值节点末行作冲排边界。
    writeFlushLines.put(writeId, rangeEndLine(node));
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
    if (occ != null) {
      String kind = valueKindFor(occ.syntaxKind);
      if (kind != null) return runtimeId(project, file, occ.range, kind);
    }
    // 字面量不是 occurrence：子树无 identifier 引用时，回退找字面量节点 → LITERAL id（供 FLOWS）
    return firstLiteralNodeId(file, subtree);
  }

  /** 在子树里找第一个字面量节点的 LITERAL id（与 emitLiteralIfAny 用同一 id，保证 FLOWS 可连）。 */
  private String firstLiteralNodeId(String file, SyntaxTree.Node subtree) {
    if (subtree == null) return null;
    if (subtree.range != null && isLiteralNodeKind(subtree.kind)) {
      return runtimeId(project, file, subtree.range, GraphModel.VALUE_KIND_LITERAL);
    }
    for (SyntaxTree.Node child : subtree.children) {
      String id = firstLiteralNodeId(file, child);
      if (id != null) return id;
    }
    return null;
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
    abruptSlotIds.add(returnId);
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
    if (valueOcc != null) {
      String valueId = firstValueRuntimeId(file, operands.get(operands.size() - 1));
      if (valueId != null && !valueId.equals(returnId)) {
        writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_VALUE, returnId);
      }
    }
    // RETURN 延迟到子节点(返回值)读取之后入链(exit 时 flush)，使 `return x` 的 NEXT 为 …→x 读→RETURN。
    pendingReturnChains.push(returnId);
    // Record this return slot against the enclosing method for cross-method return binding.
    String methodSymbol = methodSymbols.isEmpty() ? null : methodSymbols.peek();
    if (methodSymbol != null && !methodSymbol.isEmpty()) {
      returnsByMethod.computeIfAbsent(methodSymbol, k -> new ArrayList<>()).add(returnId);
    }
  }

  /**
   * {@code throw}：与 {@link #handleReturn} 对称——非正常出口，同样终止本块。
   *
   * <p>建 THROW 槽节点（即使 `throw;` 不合法，`throw new E()` 也总有一个操作数），
   * 延迟到抛出表达式读取之后入链（`…→ new E() 读 → THROW`），与 RETURN 的 `…→x 读→RETURN` 一致。
   * 槽的 symbol 记为抛出表达式的符号，便于回溯"抛的是什么"。
   *
   * <p>与 return 的差别：throw 不写 returnsByMethod（它不是"返回"），但仍标记 hasReturn，
   * 因为对数据流合并而言它同样是"该分支未正常落到后续代码"。
   */
  private void handleThrow(String file, SyntaxTree.Node node) {
    Scope cur = currentScope();
    if (cur != null) cur.hasReturn = true;
    String throwId = runtimeId(project, file, node.range, GraphModel.VALUE_KIND_THROW);
    abruptSlotIds.add(throwId);
    SyntaxTree.OccurrenceData valueOcc = null;
    List<SyntaxTree.Node> operands = nonWhitespaceChildren(node);
    if (!operands.isEmpty()) {
      valueOcc = firstValueReference(operands.get(operands.size() - 1));
    }
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", "throw");
    props.put("symbol", valueOcc != null ? valueOcc.symbol : "");
    props.put("file", file);
    props.put("line", rangeLine(node));
    props.put("col", rangeCol(node));
    props.put("colEnd", rangeColEnd(node));
    props.put("kind", GraphModel.VALUE_KIND_THROW);
    props.put("access", "write");
    writer.addNode(GraphModel.LABEL_VALUE, throwId, props);
    if (valueOcc != null) {
      String valueId = firstValueRuntimeId(file, operands.get(operands.size() - 1));
      if (valueId != null && !valueId.equals(throwId)) {
        writer.addEdge(GraphModel.REL_FLOWS, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_VALUE, throwId);
      }
    }
    // 与 RETURN 同样延迟到操作数读取之后入链（exit 时 flush）。
    pendingThrowChains.push(throwId);
  }

  // ---------------------------------------------------------------------------
  // Call layer
  // ---------------------------------------------------------------------------

  private void enterInvocation(String file, SyntaxTree.Node node, SyntaxTree.Node parent) {
    String symbol = invocationSymbol(node);
    if (symbol == null) return;
    String id = runtimeId(project, file, node.range, null);
    Map<String, Object> props = new LinkedHashMap<>();
    String dispName;
    if (ScipSymbols.isLocal(symbol)) {
      // 局部符号（匿名对象/lambda/局部函数）没有可真名：有源码局部名用其名，否则保留完整 `local N`
      // （而不是被 shortName 截成裸数字 N），后置补正。
      Map<String, String> m = localNamesByFile.get(file);
      String sn = m == null ? null : m.get(symbol);
      dispName = (sn != null && !sn.isEmpty()) ? sn : symbol;
      pendingLocalCallNames.add(new String[] {id, file, symbol});
    } else {
      dispName = shortName(symbol);
    }
    props.put("name", dispName);
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

    // 调用点(以及其 Value 数据作用域)经 NEXT 链被其条件/方法根遍历到，不再用 LEADS_TO 逐节点锚定。

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
        slotName = cleanParamName(calleeParamNames.get(argIndex), argIndex);
        if (calleeParamSyms != null && argIndex < calleeParamSyms.size()) slotSym = calleeParamSyms.get(argIndex);
        else slotSym = paramSymbolFor(symbol, slotName); // 外部方法：按签名合成 方法().(形参)
      }
      String valueSymbol = slotSym != null ? slotSym : (argSym != null ? argSym : null);
      String name = cleanParamName(slotName != null ? slotName : (argSym != null && !argSym.isEmpty() ? valueName(file, argSym) : null), argIndex);
      // 形参/实参符号都取不到（实参全为局部变量）时，用干净名字合成形参符号，避免 symbol 落空。
      if ((valueSymbol == null || valueSymbol.isEmpty()) && isPlainName(name)) valueSymbol = paramSymbolFor(symbol, name);
      String safeSym = valueSymbol == null || valueSymbol.isEmpty() ? ("#" + argIndex) : valueSymbol;
      String valueId = runtimeId(project, file, arg.range, argIndex + ":" + safeSym);
      Map<String, Object> argProps = new LinkedHashMap<>();
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
      if (child != null && child.range != null && isLiteralNodeKind(child.kind)) {
        out.add(runtimeId(project, file, child.range, GraphModel.VALUE_KIND_LITERAL)); // `if(true)` 的 true → CONTROLS
      }
      collectValueIds(file, child, out);
    }
  }

  /** The receiver chain child of an invocation (the object expression being called on), or null. */
  private static SyntaxTree.Node receiverSubtree(SyntaxTree.Node node) {
    for (SyntaxTree.Node child : node.children) {
      if (isMemberSelectKind(child.kind)) {
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
    // 本条件自身 id 压入作用域栈（供 walkConditionChildren 取当前条件作分支锚点）。不建 SUB/ELSE——
    // 条件之间(含 else-if 链)的连接一律由 NEXT 表达。
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
    if (expr == null) return out;
    // 守卫是谓词调用时(如 `while (f(x))`、`if (f(x))`)，守卫值是其返回(CALLED_RETURN)，而非实参读 `x`。
    // 条件表达式可能被包装(非直接调用节点)，故递归找其中首个调用。
    SyntaxTree.Node call = findConditionCall(expr);
    if (call != null) {
      out.add(runtimeId(project, file, call.range, GraphModel.VALUE_KIND_CALLED_RETURN));
      return out;
    }
    collectValueIds(file, expr, out);
    return out;
  }

  /** 条件表达式内首个谓词调用节点(守卫)，无则 null。 */
  private static SyntaxTree.Node findConditionCall(SyntaxTree.Node expr) {
    if (expr == null) return null;
    if (isInvocationKind(expr.kind)) return expr;
    for (SyntaxTree.Node child : expr.children) {
      SyntaxTree.Node r = findConditionCall(child);
      if (r != null) return r;
    }
    return null;
  }

  /** 循环条件的"首事件"id=条件表达式**首个值读**(实参/左值,如 `while(f(code))` 的 `code`)。
   *  loop 回边应指向它(循环再次执行从条件求值的第一步进入),而非 CALLED_RETURN(末事件)或 LOOP 标记节点。
   *  注意:CONTROLS 的守卫仍取自谓词调用的 CALLED_RETURN(见 conditionValueIds),两者不同目标。 */
  private String loopConditionEventId(String file, SyntaxTree.Node node) {
    SyntaxTree.Node expr = conditionExpression(node);
    if (expr == null) return null;
    List<String> ids = new ArrayList<>();
    collectValueIds(file, expr, ids);
    return ids.isEmpty() ? null : ids.get(0);
  }

  private static SyntaxTree.Node conditionExpression(SyntaxTree.Node node) {
    if (node.kind.equals("IF")) {
      // Kotlin 的 if 子节点形如 [if][WHITE][LPAR][CONDITION][RPAR]...,条件是 CONDITION 子节点;
      // 若按"首个非关键字子节点"会取到 LPAR,取不到守卫、CONTROLS 缺失(Java 的 IF 首子节点即条件)。
      for (SyntaxTree.Node child : node.children) {
        if (child.kind.equals("CONDITION")) return child;
      }
      for (SyntaxTree.Node child : node.children) {
        String k = child.kind;
        if (k.equals("WHITE_SPACE")
            || k.equals("THEN")
            || k.equals("ELSE")
            || k.equals("else")
            || k.equals("IF_KEYWORD")
            || k.equals("KEYWORD")
            || k.equals("if")) {
          continue;
        }
        return child;
      }
      return null;
    }
    // do { body } while (cond):条件在末(体在前)。
    if (isKind(node.kind, NodeKind.DO_WHILE_LOOP)) {
      List<SyntaxTree.Node> kids = nonWhitespaceChildren(node);
      return kids.isEmpty() ? null : kids.get(kids.size() - 1);
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

  /** 判断某 kind 是否属于规范词表：先经 {@link NodeKind#canonical} 归一，再按规范名匹配。这样
   *  Java 原生 kind（如 {@code WHILE_LOOP}/{@code MEMBER_SELECT}）与 Kotlin 原生 kind
   *  （如 {@code WHILE}/{@code DOT_QUALIFIED_EXPRESSION}）都归到同一词表判类，无需逐处区分语言。 */
  private static boolean isKind(String kind, String... canonical) {
    String k = NodeKind.canonical(kind);
    if (k == null) return false;
    for (String c : canonical) if (c.equals(k)) return true;
    return false;
  }

  private static boolean isTypeKind(String kind) {
    return isKind(kind, "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE",
        "OBJECT_DECLARATION", "OBJECT_LITERAL", "TYPEALIAS", "companion");
  }

  private static boolean isMethodKind(String kind) {
    // FUN / PRIMARY_CONSTRUCTOR / SECONDARY_CONSTRUCTOR 经 NodeKind 归一到 METHOD。
    return isKind(kind, NodeKind.METHOD);
  }

  private static boolean isConditionKind(String kind) {
    // WHILE/FOR/DO_WHILE 归一到 WHILE_LOOP/FOR_LOOP/DO_WHILE_LOOP；Kotlin WHEN 透传。
    return isKind(kind, "IF", NodeKind.WHILE_LOOP, NodeKind.FOR_LOOP, "ENHANCED_FOR_LOOP",
        NodeKind.DO_WHILE_LOOP, "WHEN");
  }

  private static boolean isLoopKind(String kind) {
    return isKind(kind, NodeKind.WHILE_LOOP, NodeKind.FOR_LOOP, "ENHANCED_FOR_LOOP",
        NodeKind.DO_WHILE_LOOP);
  }

  private static boolean isInvocationKind(String kind) {
    // CALL_EXPRESSION→METHOD_INVOCATION，CONSTRUCTOR_CALL→NEW_CLASS。
    return isKind(kind, NodeKind.METHOD_INVOCATION, NodeKind.NEW_CLASS);
  }

  private static boolean isMemberSelectKind(String kind) {
    // DOT_QUALIFIED_EXPRESSION / SAFE_ACCESS_EXPRESSION 归一到 MEMBER_SELECT。
    return isKind(kind, NodeKind.MEMBER_SELECT);
  }

  private static boolean isIndexAccessKind(String kind) {
    // ARRAY_ACCESS_EXPRESSION 归一到 ARRAY_ACCESS。
    return isKind(kind, NodeKind.ARRAY_ACCESS);
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
    if (!baseId.equals(elementId)) {
      writer.addEdge(GraphModel.REL_INDEX, GraphModel.LABEL_VALUE, baseId, GraphModel.LABEL_VALUE, elementId);
    }
  }

  private static boolean isReturnKind(String kind) {
    return kind.equals("RETURN");
  }

  private static boolean isThrowKind(String kind) {
    return kind.equals("THROW");
  }

  /** 该节点是否是"语句序列"（其 children 是同级语句，末条 abrupt 即后续不可达）。 */
  private static boolean isStatementSequence(String kind) {
    return isKind(kind, "BLOCK", "CLASS_BODY", "BODY", "FILE", "COMPILATION_UNIT")
        || isMethodKind(kind);
  }

  /**
   * 语句序列的<b>末条</b>语句是否必然 abrupt 地离开（return/throw 语句）。
   *
   * <p>刻意只认"裸的、或仅由语句包装层包裹的" return/throw——即该语句本身无条件离开。
   * 以下都<b>不</b>算：
   * <ul>
   *   <li>{@code if (c) return;} / 循环 / when —— 条件分支，条件不成立时仍会继续；
   *   <li>表达式里的 throw（如 {@code val x = y ?: throw …}）—— 该语句整体是"声明/赋值"，
   *       不是"抛出语句"；按其语义该变量仍被声明、后续语句可达。
   * </ul>
   * 故判定只看"直接子语句"这一层，且遇到条件/循环/表达式即停，不深入下潜。
   */
  private static boolean hasAbruptStatement(List<SyntaxTree.Node> children) {
    for (SyntaxTree.Node n : nonWhitespaceChildren(children)) {
      if (isAbruptStatementNode(n)) return true;
    }
    return false;
  }

  /** 该语句节点是否就是一条 return/throw 语句（允许透过语句包装层，但不深入条件/表达式）。 */
  private static boolean isAbruptStatementNode(SyntaxTree.Node n) {
    if (n == null) return false;
    if (isReturnKind(n.kind) || isThrowKind(n.kind)) return true;
    // 条件/循环/switch/when：可能有不进入分支的路径，不算必然离开。
    if (isConditionKind(n.kind) || isLoopKind(n.kind)) return false;
    // 语句包装层（如 javac 的 EXPRESSION_STATEMENT 包 BREAK 那类）：其子若恰为单条语句则透传。
    // 只透传"语句容器"，不透传表达式——表达式里的 throw（elvis 等）不属于抛出语句。
    if (isStatementWrapper(n.kind)) {
      List<SyntaxTree.Node> kids = nonWhitespaceChildren(n);
      return kids.size() == 1 && isAbruptStatementNode(kids.get(0));
    }
    return false;
  }

  /** 仅包裹一条语句的包装节点（可透传其子以判定 abrupt）。 */
  private static boolean isStatementWrapper(String kind) {
    return kind.equals("LABELED_STATEMENT") || kind.equals("EXPRESSION_STATEMENT");
  }

  /** nonWhitespaceChildren 的列表重载。 */
  private static List<SyntaxTree.Node> nonWhitespaceChildren(List<SyntaxTree.Node> nodes) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    for (SyntaxTree.Node n : nodes) {
      if (n == null) continue;
      if (n.kind == null || n.kind.isEmpty() || n.kind.equals("WHITE_SPACE")) continue;
      out.add(n);
    }
    return out;
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
    } else if (isKind(node.kind, NodeKind.DO_WHILE_LOOP)) {
      // do { body } while (cond):body 先执行、条件在末，故主体是 do 体(通常为 BLOCK)，不能像 while/for 那样
      // 取"最后一个孩子"(那是条件)。取主体块；若主体是单语句(非块)则回退到倒数第二个孩子。
      for (SyntaxTree.Node k : kids) {
        if (k.kind.equals("BLOCK")) out.add(k);
      }
      if (out.isEmpty() && kids.size() >= 2) out.add(kids.get(kids.size() - 2));
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
    if (ScipSymbols.isLocal(symbol)) return symbols.get(file + "\u0000" + symbol);
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
      // 优先用走树时登记的源码名（最贴近该处用法）。
      Map<String, String> fileNames = localNamesByFile.get(file);
      String n = fileNames == null ? null : fileNames.get(symbol);
      if (n != null && !n.isEmpty()) return n;
      // 其次回退到索引里的 SymbolInformation：并非所有局部符号都有"被 walk 到的声明"
      // （如只出现在未展开的表达式里的中间变量），但索引里仍有 display_name。
      // 无此回退时这些读节点只能显示裸数字（shortName 对 "local N" 砍前缀）。
      SymbolInformation info = infoOf(file, symbol);
      if (info != null && !info.getDisplayName().isEmpty()) return info.getDisplayName();
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

  private static int rangeEndLine(SyntaxTree.Node node) {
    return node.range == null ? 0 : node.range.endLine();
  }

  /** 声明语句的末行：javac 的 def 挂在声明节点(如 VARIABLE，自身已含 initializer)上，用 node 末行；
   *  Kotlin 的 def 挂在 LHS IDENTIFIER 上(行号仅到 LHS)，RHS 是其兄弟——取父(声明)节点末行。
   *  父若是块体(非声明容器)则退回 node，避免把写推迟到块末。 */
  private static int declarationEndLine(SyntaxTree.Node node, SyntaxTree.Node parent) {
    int end = rangeEndLine(node);
    if (parent != null && parent.range != null && !isBlockBodyKind(parent.kind)) {
      int pe = rangeEndLine(parent);
      if (pe > end) end = pe;
    }
    return end;
  }

  private static boolean isBlockBodyKind(String kind) {
    return isMethodKind(kind)
        || isKind(kind, "BLOCK", "CLASS_BODY", "BODY", "FILE", "COMPILATION_UNIT");
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

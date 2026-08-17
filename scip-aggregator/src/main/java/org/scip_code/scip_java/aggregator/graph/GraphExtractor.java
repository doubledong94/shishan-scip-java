package org.scip_code.scip_java.aggregator.graph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip_java.shared.ScipRange;
import org.scip_code.scip_java.shared.ScipSymbols;
import org.scip_code.scip_java.shared.SyntaxTree;

/**
 * Walks the merged per-file syntax trees and rewritten {@link SymbolInformation} and streams the
 * code graph into a {@link Neo4jGraphWriter}.
 *
 * <p>What this first pass produces (see {@code shishanMcp/doc/GRAPH_MODEL.md}):
 *
 * <ul>
 *   <li>declaration layer: {@code Class} / {@code Method} / {@code Field} nodes plus {@code Value}
 *       nodes (PARAM / LOCAL_VAR), wired with {@code DECLARES} / {@code HAS_PARAM} / {@code
 *       EXTENDS} / {@code OVERRIDES};
 *   <li>call layer: {@code CalledMethod} per invocation + {@code CALLS} to the declared method
 *       (project-defined targets only) + argument {@code Value}s with {@code ARG_OF} + {@code
 *       SCOPED_BY};
 *   <li>branch layer: {@code Condition} nodes (if/loops) wired with {@code ROOT} / {@code SUB} and
 *       {@code LEADS_TO} from the branches that reach a call.
 * </ul>
 *
 * <p>Data-flow ({@code FLOWS}), {@code CONTROLS}, {@code REF} and precise else-if ({@code ELSE})
 * edges are intentionally deferred to a later pass.
 */
public final class GraphExtractor {

  private final GraphSink writer;
  private final String project;
  private final Map<String, SymbolInformation> symbols;

  private final Deque<String> classFrames = new ArrayDeque<>();
  private final Deque<String> methodFrames = new ArrayDeque<>();
  private final Deque<String> methodRootConds = new ArrayDeque<>();
  private final Deque<String> conds = new ArrayDeque<>();

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
    walk(file, root);
  }

  /** Emits declaration-relationship edges ({@code EXTENDS} / {@code OVERRIDES}). */
  public void emitRelationships() {
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

  private void walk(String file, SyntaxTree.Node node) {
    enter(file, node);
    for (SyntaxTree.Node child : node.children) {
      walk(file, child);
    }
    exit(node);
  }

  private void enter(String file, SyntaxTree.Node node) {
    SyntaxTree.OccurrenceData def = definition(node);
    if (def != null) {
      enterDeclaration(file, node, def);
      return;
    }
    if (isInvocationKind(node.kind)) {
      enterInvocation(file, node);
    }
    if (isConditionKind(node.kind)) {
      enterCondition(file, node);
    }
  }

  private void exit(SyntaxTree.Node node) {
    if (isTypeNode(node.kind) && !classFrames.isEmpty()) classFrames.pop();
    if (node.kind.equals("METHOD")) {
      if (!methodFrames.isEmpty()) methodFrames.pop();
      if (!methodRootConds.isEmpty()) {
        methodRootConds.pop();
        if (!conds.isEmpty()) conds.pop(); // the method root condition
      }
    }
    if (isConditionKind(node.kind) && !conds.isEmpty()) conds.pop();
  }

  // ---------------------------------------------------------------------------
  // Declarations
  // ---------------------------------------------------------------------------

  private void enterDeclaration(
      String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def) {
    String symbol = def.symbol;
    if (symbol.isEmpty()) return;
    String id = declId(project, file, symbol);
    SymbolInformation info = symbols.get(symbol);
    String syntaxKind = def.syntaxKind;
    String displayName =
        info != null && !info.getDisplayName().isEmpty()
            ? info.getDisplayName()
            : shortName(symbol);
    int line = def.range == null ? 0 : def.range.startLine();

    if (isTypeNode(node.kind)) {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("name", displayName);
      props.put("file", file);
      props.put("line", line);
      props.put("symbol", symbol);
      props.put("kind", typeKind(info));
      writer.addNode(GraphModel.LABEL_CLASS, id, props);
      if (!classFrames.isEmpty()) {
        writer.addEdge(GraphModel.REL_DECLARES, GraphModel.LABEL_CLASS, classFrames.peek(), GraphModel.LABEL_CLASS, id);
      }
      classFrames.push(id);
    } else if (node.kind.equals("METHOD")) {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("name", displayName);
      props.put("file", file);
      props.put("line", line);
      props.put("symbol", symbol);
      boolean isConstructor = info != null && info.getKind() == SymbolInformation.Kind.Constructor;
      props.put("isConstructor", isConstructor);
      if (info != null && info.hasSignatureDocumentation()) {
        props.put("signature", info.getSignatureDocumentation().getText());
      }
      writer.addNode(GraphModel.LABEL_METHOD, id, props);
      if (!classFrames.isEmpty()) {
        writer.addEdge(GraphModel.REL_DECLARES, GraphModel.LABEL_CLASS, classFrames.peek(), GraphModel.LABEL_METHOD, id);
      }
      methodFrames.push(id);
      // Method root condition anchors SCOPED_BY for body-level runtime nodes.
      String rootCond = runtimeId(project, file, node.range, "root");
      Map<String, Object> rootProps = new LinkedHashMap<>();
      rootProps.put("file", file);
      rootProps.put("line", line);
      rootProps.put("kind", GraphModel.CONDITION_KIND_METHOD);
      writer.addNode(GraphModel.LABEL_CONDITION, rootCond, rootProps);
      writer.addEdge(GraphModel.REL_ROOT, GraphModel.LABEL_METHOD, id, GraphModel.LABEL_CONDITION, rootCond);
      methodRootConds.push(rootCond);
      conds.push(rootCond);
    } else if (node.kind.equals("VARIABLE")) {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("name", displayName);
      props.put("file", file);
      props.put("line", line);
      props.put("symbol", symbol);
      if ("IdentifierParameter".equals(syntaxKind)) {
        props.put("kind", GraphModel.VALUE_KIND_PARAM);
        writer.addNode(GraphModel.LABEL_VALUE, id, props);
        if (!methodFrames.isEmpty()) {
          writer.addEdge(GraphModel.REL_HAS_PARAM, GraphModel.LABEL_METHOD, methodFrames.peek(), GraphModel.LABEL_VALUE, id);
        }
      } else if ("IdentifierConstant".equals(syntaxKind)) {
        props.put("kind", "field");
        writer.addNode(GraphModel.LABEL_FIELD, id, props);
        if (!classFrames.isEmpty()) {
          writer.addEdge(GraphModel.REL_DECLARES, GraphModel.LABEL_CLASS, classFrames.peek(), GraphModel.LABEL_FIELD, id);
        }
      } else {
        props.put("kind", GraphModel.VALUE_KIND_LOCAL_VAR);
        writer.addNode(GraphModel.LABEL_VALUE, id, props);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Call layer
  // ---------------------------------------------------------------------------

  private void enterInvocation(String file, SyntaxTree.Node node) {
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

    // Argument values → ARG_OF.
    int argIndex = 0;
    for (SyntaxTree.Node arg : node.children) {
      if (hasSymbol(arg, symbol)) continue; // the receiver / type select, not an argument
      String valueSymbol = argValueSymbol(arg);
      String valueId =
          runtimeId(project, file, arg.range, argIndex + ":" + (valueSymbol != null ? valueSymbol : "arg"));
      Map<String, Object> argProps = new LinkedHashMap<>();
      argProps.put("name", valueSymbol != null ? shortName(valueSymbol) : "arg");
      argProps.put("symbol", valueSymbol != null ? valueSymbol : "");
      argProps.put("file", file);
      argProps.put("line", arg.range == null ? 0 : arg.range.startLine());
      argProps.put("kind", GraphModel.VALUE_KIND_CALLED_PARAM);
      writer.addNode(GraphModel.LABEL_VALUE, valueId, argProps);
      writer.addEdge(GraphModel.REL_ARG_OF, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CALLED_METHOD, id);
      if (scope != null) {
        writer.addEdge(GraphModel.REL_SCOPED_BY, GraphModel.LABEL_VALUE, valueId, GraphModel.LABEL_CONDITION, scope);
      }
      argIndex++;
    }
  }

  // ---------------------------------------------------------------------------
  // Conditions
  // ---------------------------------------------------------------------------

  private void enterCondition(String file, SyntaxTree.Node node) {
    String id = runtimeId(project, file, node.range, null);
    String kind =
        isLoopKind(node.kind) ? GraphModel.CONDITION_KIND_LOOP : GraphModel.CONDITION_KIND_IF;
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("file", file);
    props.put("line", node.range == null ? 0 : node.range.startLine());
    props.put("kind", kind);
    writer.addNode(GraphModel.LABEL_CONDITION, id, props);

    String parent = innermostCond();
    if (parent != null) {
      writer.addEdge(GraphModel.REL_SUB, GraphModel.LABEL_CONDITION, parent, GraphModel.LABEL_CONDITION, id);
    }
    conds.push(id);
  }

  private String innermostCond() {
    return conds.isEmpty() ? null : conds.peek();
  }

  private boolean isMethodRoot(String condId) {
    return !methodRootConds.isEmpty() && condId.equals(methodRootConds.peek());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static boolean isTypeNode(String kind) {
    return kind.equals("CLASS")
        || kind.equals("INTERFACE")
        || kind.equals("ENUM")
        || kind.equals("RECORD")
        || kind.equals("ANNOTATION_TYPE");
  }

  private static boolean isConditionKind(String kind) {
    return kind.equals("IF")
        || kind.equals("WHILE_LOOP")
        || kind.equals("FOR_LOOP")
        || kind.equals("ENHANCED_FOR_LOOP");
  }

  private static boolean isLoopKind(String kind) {
    return kind.equals("WHILE_LOOP") || kind.equals("FOR_LOOP") || kind.equals("ENHANCED_FOR_LOOP");
  }

  private static boolean isInvocationKind(String kind) {
    return kind.equals("METHOD_INVOCATION") || kind.equals("NEW_CLASS");
  }

  private static SyntaxTree.OccurrenceData definition(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 1) return occ;
    }
    return null;
  }

  private static String invocationSymbol(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 0 && isFunctionSyntax(occ.syntaxKind)) return occ.symbol;
    }
    if (!node.children.isEmpty()) {
      for (SyntaxTree.OccurrenceData occ : node.children.get(0).occurrences) {
        if (occ.role == 0 && isFunctionSyntax(occ.syntaxKind)) return occ.symbol;
      }
    }
    return null;
  }

  private static boolean isFunctionSyntax(String syntaxKind) {
    if (syntaxKind == null) return false;
    return syntaxKind.equals("IdentifierFunction")
        || syntaxKind.equals("IdentifierFunctionDefinition");
  }

  private static boolean hasSymbol(SyntaxTree.Node node, String symbol) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (symbol.equals(occ.symbol)) return true;
    }
    return false;
  }

  /** Best-effort value symbol for an invocation argument (first reference occurrence). */
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

  private static String typeKind(SymbolInformation info) {
    if (info == null) return "class";
    return switch (info.getKind()) {
      case Interface -> "interface";
      case Enum -> "enum";
      case TypeParameter -> "type";
      default -> "class";
    };
  }

  /** Extracts a readable name from a SCIP symbol (fallback when no SymbolInformation). */
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
}

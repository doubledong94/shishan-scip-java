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
 *   <li>javac: definition occurrences sit on the structural node itself ({@code CLASS} / {@code
 *       METHOD} / {@code VARIABLE});
 *   <li>scip-kotlinc: the tree is the Kotlin PSI lighter-AST; definitions sit on the name {@code
 *       IDENTIFIER} token under the structural node ({@code CLASS} / {@code FUN} / ...), and method
 *       / call nodes are named {@code FUN} / {@code CALL_EXPRESSION}.
 * </ul>
 *
 * <p>Declaration nodes are created from definition occurrences (classified by SCIP {@code
 * syntaxKind}); containment ({@code DECLARES} / {@code HAS_PARAM}) is derived from the SCIP symbol
 * hierarchy in a post-pass (robust across both front-ends), while the tree provides the branch and
 * call structure ({@code Condition} with {@code ROOT}/{@code SUB}/{@code LEADS_TO}, {@code
 * CalledMethod} with {@code CALLS}/{@code ARG_OF}/{@code SCOPED_BY}). Data-flow ({@code FLOWS}),
 * {@code CONTROLS}, {@code REF} and precise else-if ({@code ELSE}) edges are deferred to a later
 * pass.
 */
public final class GraphExtractor {

  private final GraphSink writer;
  private final String project;
  private final Map<String, SymbolInformation> symbols;

  // Post-pass bookkeeping: created declaration symbols → node label.
  private final Map<String, String> createdSymbolLabel = new LinkedHashMap<>();

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

  /**
   * Emits the declaration-relationship edges:
   *
   * <ul>
   *   <li>{@code DECLARES} / {@code HAS_PARAM} derived from the SCIP symbol hierarchy;
   *   <li>{@code EXTENDS} / {@code OVERRIDES} from {@link SymbolInformation} relationships.
   * </ul>
   */
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

  private void walk(String file, SyntaxTree.Node node) {
    enter(file, node);
    for (SyntaxTree.Node child : node.children) {
      walk(file, child);
    }
    exit(node);
  }

  private void enter(String file, SyntaxTree.Node node) {
    // Method scope: a structural method node (javac METHOD / Kotlin FUN) anchors a root condition
    // so body-level calls can be SCOPED_BY it.
    if (isMethodKind(node.kind)) {
      SyntaxTree.OccurrenceData def = structuralDefinition(node);
      if (def != null) pushMethodScope(file, node, def);
    }

    // Create a declaration node for every definition occurrence (MERGE dedups by id when a
    // structural node and its name token carry the same definition).
    SyntaxTree.OccurrenceData def = definition(node);
    if (def != null) createDeclaration(file, node, def);

    if (isInvocationKind(node.kind)) {
      enterInvocation(file, node);
    }
    if (isConditionKind(node.kind)) {
      enterCondition(file, node);
    }
  }

  private void exit(SyntaxTree.Node node) {
    if (isConditionKind(node.kind) && !conds.isEmpty()) conds.pop();
    if (isMethodKind(node.kind)) {
      if (!methodRootConds.isEmpty()) methodRootConds.pop();
      if (!conds.isEmpty()) conds.pop(); // method root condition
    }
  }

  // ---------------------------------------------------------------------------
  // Declarations
  // ---------------------------------------------------------------------------

  private void pushMethodScope(String file, SyntaxTree.Node node, SyntaxTree.OccurrenceData def) {
    String symbol = def.symbol;
    if (symbol.isEmpty() || ScipSymbols.isLocal(symbol)) return;
    String id = declId(project, file, symbol);
    String rootCond = runtimeId(project, file, def.range, "root");
    Map<String, Object> rootProps = new LinkedHashMap<>();
    rootProps.put("file", file);
    rootProps.put("line", rangeLine(def));
    rootProps.put("kind", GraphModel.CONDITION_KIND_METHOD);
    writer.addNode(GraphModel.LABEL_CONDITION, rootCond, rootProps);
    writer.addEdge(GraphModel.REL_ROOT, GraphModel.LABEL_METHOD, id, GraphModel.LABEL_CONDITION, rootCond);
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
      label = GraphModel.LABEL_CLASS;
    } else if ("IdentifierFunctionDefinition".equals(syntaxKind)) {
      boolean isConstructor =
          info != null && info.getKind() == SymbolInformation.Kind.Constructor;
      props.put("isConstructor", isConstructor);
      label = GraphModel.LABEL_METHOD;
    } else if ("IdentifierParameter".equals(syntaxKind)) {
      props.put("kind", GraphModel.VALUE_KIND_PARAM);
      label = GraphModel.LABEL_VALUE;
    } else if ("IdentifierLocal".equals(syntaxKind)) {
      props.put("kind", GraphModel.VALUE_KIND_LOCAL_VAR);
      label = GraphModel.LABEL_VALUE;
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
    List<SyntaxTree.Node> args = argumentNodes(node);
    int argIndex = 0;
    for (SyntaxTree.Node arg : args) {
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
        || kind.equals("DO_WHILE");
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

  // ---------------------------------------------------------------------------
  // Occurrence helpers
  // ---------------------------------------------------------------------------

  private static SyntaxTree.OccurrenceData definition(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 1) return occ;
    }
    return null;
  }

  /**
   * The structural node's own definition: the def on the node itself, or the first def among its
   * direct children. For javac the def sits on the node; for Kotlin it sits on the name {@code
   * IDENTIFIER} direct child. Deliberately shallow so nested members' defs are never mistaken for
   * the node's own.
   */
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
    SyntaxTree.OccurrenceData found = functionReference(node);
    if (found != null) return found.symbol;
    // Kotlin: callee reference sits on a direct child (OPERATION_REFERENCE / REFERENCE_EXPRESSION).
    for (SyntaxTree.Node child : node.children) {
      SyntaxTree.OccurrenceData d = functionReference(child);
      if (d != null) return d.symbol;
    }
    return null;
  }

  private static SyntaxTree.OccurrenceData functionReference(SyntaxTree.Node node) {
    for (SyntaxTree.OccurrenceData occ : node.occurrences) {
      if (occ.role == 0 && isFunctionSyntax(occ.syntaxKind)) return occ;
    }
    return null;
  }

  private static boolean isFunctionSyntax(String syntaxKind) {
    if (syntaxKind == null) return false;
    return syntaxKind.equals("IdentifierFunction")
        || syntaxKind.equals("IdentifierFunctionDefinition");
  }

  /** The argument expression nodes of an invocation. */
  private static List<SyntaxTree.Node> argumentNodes(SyntaxTree.Node node) {
    List<SyntaxTree.Node> out = new ArrayList<>();
    String calleeSymbol = invocationSymbol(node);
    for (SyntaxTree.Node child : node.children) {
      if (child.kind.equals("VALUE_ARGUMENT_LIST")) {
        for (SyntaxTree.Node va : child.children) {
          if (va.kind.equals("VALUE_ARGUMENT")) out.add(va);
        }
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

  // ---------------------------------------------------------------------------
  // SCIP symbol hierarchy
  // ---------------------------------------------------------------------------

  /** Owner symbol (parent) of a SCIP symbol, or null when it has no parent. */
  static String ownerOf(String symbol) {
    if (symbol == null || symbol.isEmpty()) return null;
    if (symbol.endsWith(").")) {
      // method descriptor name(disambiguator)(params). → owner is the type/term before the name
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
      // parameter descriptor (name)
      int i = symbol.length() - 2;
      while (i >= 0 && symbol.charAt(i) != '(') i--;
      return i < 0 ? null : symbol.substring(0, i);
    }
    if (symbol.endsWith("#")) {
      // type descriptor name#
      int i = symbol.length() - 2;
      while (i >= 0 && isNameChar(symbol.charAt(i))) i--;
      return i < 0 ? null : symbol.substring(0, i + 1);
    }
    if (symbol.endsWith(".")) {
      // term descriptor name.
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

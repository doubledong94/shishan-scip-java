package org.scip_code.scip_java.shared;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.ArrayList;
import java.util.List;

/**
 * A full per-file syntax tree that replaces the flat SCIP {@code occurrences} list.
 *
 * <p>Each node corresponds to a compiler syntax node and carries:
 *
 * <ul>
 *   <li>{@code kind}: the syntax node's kind (e.g. javac's {@code CLASS}/{@code METHOD}, or a
 *       Kotlin element type name),
 *   <li>{@code range}: the node's full source span,
 *   <li>{@code occurrences}: zero or more resolved symbols attached to this node (symbol, SCIP role,
 *       SCIP syntax kind and the precise token range),
 *   <li>{@code children}: nested syntax nodes.
 * </ul>
 *
 * <p>The model is serialized as a protobuf {@link Struct} so both compiler plugins (which only have
 * {@code protobuf-java}) and the aggregator/CLI (which add {@code protobuf-java-util} for JSON) can
 * read and write it without adding a JSON dependency to the plugin classpaths.
 */
public final class SyntaxTree {

  private SyntaxTree() {}

  public static final class Node {
    public String kind = "";
    public ScipRange range;
    public final List<OccurrenceData> occurrences = new ArrayList<>();
    public final List<Node> children = new ArrayList<>();

    public Node() {}

    public Node(String kind) {
      this.kind = kind;
    }
  }

  /** A resolved symbol attached to a syntax node (replaces a flat SCIP occurrence). */
  public static final class OccurrenceData {
    public String symbol = "";
    /** SCIP SymbolRole value (0 = reference, 1 = definition). */
    public int role;
    /** SCIP SyntaxKind name, or null when unspecified. */
    public String syntaxKind;
    /** The precise token range (may be narrower than the node's own range). */
    public ScipRange range;
    /** The enclosing construct's range, when known. */
    public ScipRange enclosingRange;

    public OccurrenceData() {}

    public OccurrenceData(String symbol, int role, String syntaxKind, ScipRange range) {
      this.symbol = symbol;
      this.role = role;
      this.syntaxKind = syntaxKind;
      this.range = range;
    }
  }

  // =======================================
  // Struct codec
  // =======================================

  private static final String KIND = "kind";
  private static final String RANGE = "range";
  private static final String ENCLOSING_RANGE = "enclosingRange";
  private static final String OCCURRENCES = "occurrences";
  private static final String CHILDREN = "children";
  private static final String SYMBOL = "symbol";
  private static final String ROLE = "role";
  private static final String SYNTAX_KIND = "syntaxKind";
  private static final String START_LINE = "startLine";
  private static final String START_CHARACTER = "startCharacter";
  private static final String END_LINE = "endLine";
  private static final String END_CHARACTER = "endCharacter";

  /** Serializes a node (and its subtree) as a protobuf {@link Struct}. */
  public static Struct toStruct(Node node) {
    Struct.Builder b = Struct.newBuilder();
    if (!node.kind.isEmpty()) b.putFields(KIND, string(node.kind));
    if (node.range != null) b.putFields(RANGE, range(node.range));
    if (!node.occurrences.isEmpty()) {
      ListValue.Builder list = ListValue.newBuilder();
      for (OccurrenceData occ : node.occurrences) list.addValues(occurrence(occ));
      b.putFields(OCCURRENCES, Value.newBuilder().setListValue(list).build());
    }
    if (!node.children.isEmpty()) {
      ListValue.Builder list = ListValue.newBuilder();
      for (Node child : node.children) list.addValues(Value.newBuilder().setStructValue(toStruct(child)));
      b.putFields(CHILDREN, Value.newBuilder().setListValue(list).build());
    }
    return b.build();
  }

  /** Parses a node (and its subtree) from a protobuf {@link Struct}. */
  public static Node fromStruct(Struct struct) {
    Node node = new Node();
    node.kind = text(struct, KIND);
    node.range = parseRange(struct.getFieldsMap().get(RANGE));
    Value occValue = struct.getFieldsMap().get(OCCURRENCES);
    if (occValue != null && occValue.hasListValue()) {
      for (Value v : occValue.getListValue().getValuesList()) {
        if (v.hasStructValue()) node.occurrences.add(parseOccurrence(v.getStructValue()));
      }
    }
    Value childValue = struct.getFieldsMap().get(CHILDREN);
    if (childValue != null && childValue.hasListValue()) {
      for (Value v : childValue.getListValue().getValuesList()) {
        if (v.hasStructValue()) node.children.add(fromStruct(v.getStructValue()));
      }
    }
    return node;
  }

  /**
   * Rewrites every occurrence symbol (and kind-preserving metadata) in a parsed tree, returning a
   * new tree. Used by the aggregator to prefix package coordinates into node symbols.
   */
  public static Node rewriteSymbols(Node root, StringRewriter rewriter) {
    Node rewritten = new Node(root.kind);
    rewritten.range = root.range;
    for (OccurrenceData occ : root.occurrences) {
      OccurrenceData rw = new OccurrenceData();
      rw.symbol = occ.symbol.isEmpty() ? "" : rewriter.rewrite(occ.symbol);
      rw.role = occ.role;
      rw.syntaxKind = occ.syntaxKind;
      rw.range = occ.range;
      rw.enclosingRange = occ.enclosingRange;
      rewritten.occurrences.add(rw);
    }
    for (Node child : root.children) rewritten.children.add(rewriteSymbols(child, rewriter));
    return rewritten;
  }

  // =======================================
  // Flatten to SCIP occurrences
  // =======================================

  /**
   * Flattens every node's occurrences into SCIP {@link org.scip_code.scip.Occurrence} protos in
   * preorder, mirroring the flat occurrences list this tree replaces. Used by tests and by
   * consumers that still need the flat view.
   */
  public static java.util.List<org.scip_code.scip.Occurrence> flatten(Node root) {
    java.util.List<org.scip_code.scip.Occurrence> out = new java.util.ArrayList<>();
    collect(root, out);
    out.sort(
        java.util.Comparator.comparingInt(
                (org.scip_code.scip.Occurrence o) -> ScipRange.from(o).startLine())
            .thenComparingInt(o -> ScipRange.from(o).startCharacter()));
    return out;
  }

  private static void collect(
      Node node, java.util.List<org.scip_code.scip.Occurrence> out) {
    for (OccurrenceData occ : node.occurrences) {
      org.scip_code.scip.Occurrence.Builder b =
          org.scip_code.scip.Occurrence.newBuilder()
              .setSymbol(occ.symbol)
              .setSymbolRoles(occ.role);
      if (occ.syntaxKind != null && !occ.syntaxKind.isEmpty()) {
        b.setSyntaxKind(org.scip_code.scip.SyntaxKind.valueOf(occ.syntaxKind));
      }
      if (occ.range != null) {
        if (occ.range.isSingleLine()) b.setSingleLineRange(occ.range.toSingleLineRange());
        else b.setMultiLineRange(occ.range.toMultiLineRange());
      }
      if (occ.enclosingRange != null) {
        if (occ.enclosingRange.isSingleLine()) {
          b.setSingleLineEnclosingRange(occ.enclosingRange.toSingleLineRange());
        } else {
          b.setMultiLineEnclosingRange(occ.enclosingRange.toMultiLineRange());
        }
      }
      out.add(b.build());
    }
    for (Node child : node.children) collect(child, out);
  }

  /** Rewrites a single symbol string (no-op passthrough when empty). */
  public interface StringRewriter {
    String rewrite(String symbol);
  }

  // =======================================
  // Per-document sidecar wrapper
  // =======================================

  private static final String RELATIVE_PATH = "relativePath";
  private static final String LANGUAGE = "language";
  private static final String TREE = "tree";

  /**
   * Wraps a per-file tree with the document's relative path and language so the aggregator can
   * merge trees without re-deriving the path from the filename.
   */
  public static Struct toDocumentStruct(String relativePath, String language, Node root) {
    Struct.Builder b = Struct.newBuilder();
    b.putFields(RELATIVE_PATH, string(relativePath));
    b.putFields(LANGUAGE, string(language));
    b.putFields(TREE, Value.newBuilder().setStructValue(toStruct(root)).build());
    return b.build();
  }

  public static String documentRelativePath(Struct document) {
    return text(document, RELATIVE_PATH);
  }

  public static String documentLanguage(Struct document) {
    return text(document, LANGUAGE);
  }

  public static Node documentTree(Struct document) {
    Value v = document.getFieldsMap().get(TREE);
    if (v == null || !v.hasStructValue()) return null;
    return fromStruct(v.getStructValue());
  }

  private static Value occurrence(OccurrenceData occ) {
    Struct.Builder b = Struct.newBuilder();
    b.putFields(SYMBOL, string(occ.symbol));
    b.putFields(ROLE, number(occ.role));
    if (occ.syntaxKind != null && !occ.syntaxKind.isEmpty()) {
      b.putFields(SYNTAX_KIND, string(occ.syntaxKind));
    }
    if (occ.range != null) b.putFields(RANGE, range(occ.range));
    if (occ.enclosingRange != null) b.putFields(ENCLOSING_RANGE, range(occ.enclosingRange));
    return Value.newBuilder().setStructValue(b.build()).build();
  }

  private static OccurrenceData parseOccurrence(Struct struct) {
    OccurrenceData occ = new OccurrenceData();
    occ.symbol = text(struct, SYMBOL);
    Value role = struct.getFieldsMap().get(ROLE);
    if (role != null && role.hasNumberValue()) occ.role = (int) role.getNumberValue();
    occ.syntaxKind = textOrNull(struct, SYNTAX_KIND);
    occ.range = parseRange(struct.getFieldsMap().get(RANGE));
    occ.enclosingRange = parseRange(struct.getFieldsMap().get(ENCLOSING_RANGE));
    return occ;
  }

  private static Value range(ScipRange r) {
    Struct.Builder b = Struct.newBuilder();
    b.putFields(START_LINE, number(r.startLine()));
    b.putFields(START_CHARACTER, number(r.startCharacter()));
    b.putFields(END_LINE, number(r.endLine()));
    b.putFields(END_CHARACTER, number(r.endCharacter()));
    return Value.newBuilder().setStructValue(b.build()).build();
  }

  private static ScipRange parseRange(Value v) {
    if (v == null || !v.hasStructValue()) return null;
    Struct s = v.getStructValue();
    return new ScipRange(
        (int) num(s, START_LINE), (int) num(s, START_CHARACTER), (int) num(s, END_LINE), (int) num(s, END_CHARACTER));
  }

  private static double num(Struct s, String field) {
    Value v = s.getFieldsMap().get(field);
    return v != null && v.hasNumberValue() ? v.getNumberValue() : 0;
  }

  private static String text(Struct s, String field) {
    Value v = s.getFieldsMap().get(field);
    return v != null && v.hasStringValue() ? v.getStringValue() : "";
  }

  private static String textOrNull(Struct s, String field) {
    String t = text(s, field);
    return t.isEmpty() ? null : t;
  }

  private static Value string(String s) {
    return Value.newBuilder().setStringValue(s).build();
  }

  private static Value number(int n) {
    return Value.newBuilder().setNumberValue(n).build();
  }
}

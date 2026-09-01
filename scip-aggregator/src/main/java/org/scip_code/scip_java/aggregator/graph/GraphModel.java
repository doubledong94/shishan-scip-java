package org.scip_code.scip_java.aggregator.graph;

/**
 * Graph model constants for the code-graph database (see shishanMcp {@code doc/GRAPH_MODEL.md}).
 *
 * <p>Labels:
 *
 * <ul>
 *   <li>{@code Class}, {@code Method}, {@code Field}: declaration layer.
 *   <li>{@code Value}: runtime value occurrences (reads/writes/called params/returns), distinguished
 *       by the {@code kind} property.
 *   <li>{@code CalledMethod}: one call site instance (also the ARG_OF / RET_OF hub).
 *   <li>{@code Condition}: a branch node (if/else-if/else/loop).
 * </ul>
 */
public final class GraphModel {

  public static final String LABEL_CLASS = "Class";
  public static final String LABEL_METHOD = "Method";
  public static final String LABEL_FIELD = "Field";
  public static final String LABEL_VALUE = "Value";
  public static final String LABEL_CALLED_METHOD = "CalledMethod";
  public static final String LABEL_CONDITION = "Condition";

  // Declaration-layer relationships.
  public static final String REL_DECLARES = "DECLARES";
  public static final String REL_HAS_PARAM = "HAS_PARAM";
  public static final String REL_EXTENDS = "EXTENDS";
  public static final String REL_IMPLEMENTS = "IMPLEMENTS";
  public static final String REL_OVERRIDES = "OVERRIDES";

  // Runtime-layer relationships.
  public static final String REL_ROOT = "ROOT";
  public static final String REL_SUB = "SUB";
  public static final String REL_ELSE = "ELSE";
  public static final String REL_LEADS_TO = "LEADS_TO";
  public static final String REL_CALLS = "CALLS";
  public static final String REL_ARG_OF = "ARG_OF";
  public static final String REL_RET_OF = "RET_OF";
  public static final String REL_FLOWS = "FLOWS";
  public static final String REL_CONTROLS = "CONTROLS";
  public static final String REL_REF = "REF";
  public static final String REL_INDEX = "INDEX";
  public static final String REL_NEXT = "NEXT";

  // Value kinds (mirror the old viewer's KEY_TYPE_* concepts, subset relevant to the indexer).
  public static final String VALUE_KIND_PARAM = "PARAM";
  public static final String VALUE_KIND_RETURN = "RETURN";
  public static final String VALUE_KIND_FIELD = "FIELD";
  public static final String VALUE_KIND_LOCAL_VAR = "LOCAL_VAR";
  public static final String VALUE_KIND_CALLED_PARAM = "CALLED_PARAM";
  public static final String VALUE_KIND_CALLED_RETURN = "CALLED_RETURN";
  public static final String VALUE_KIND_INDEX = "INDEX";
  public static final String VALUE_KIND_DEFAULT_VALUE = "DEFAULT_VALUE";
  public static final String VALUE_KIND_KEY_WORD_VALUE = "KEY_WORD_VALUE";
  public static final String VALUE_KIND_LITERAL = "LITERAL"; // 字面量(字符串/数字/布尔等)

  // Condition kinds (javac tree kinds).
  public static final String CONDITION_KIND_METHOD = "METHOD";
  public static final String CONDITION_KIND_IF = "IF";
  public static final String CONDITION_KIND_ELSE = "ELSE";
  public static final String CONDITION_KIND_LOOP = "LOOP";

  private GraphModel() {}
}

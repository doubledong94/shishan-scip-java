package org.scip_code.scip_java.shared;

import java.util.Map;

/**
 * 统一节点 kind 词表。
 *
 * <p>javac 与 kotlinc 把各自的「语法树节点 kind」直接写进 {@link SyntaxTree.Node#kind}：Java 用
 * javac {@code Tree.Kind}（如 {@code METHOD}/{@code WHILE_LOOP}），Kotlin 用 PSI token 类型
 * （如 {@code FUN}/{@code WHILE}）。两者对同一语言构造的命名不同，消费方（GraphExtractor 等）
 * 因而要在两套词汇间桥接。
 *
 * <p>本类把这两种原生词汇映射到一套规范词表（以 Java 词汇为基准），使消费方只需按规范名判类，
 * 而不再区分来源语言。{@link #canonical} 对已经是规范名 / 未知的 kind 原样透传（幂等），
 * Kotlin 独有的构造（如 {@code OBJECT_LITERAL}/{@code WHEN}）与词法 token（{@code WHITE_SPACE}
 * 等）不做映射、保持原样。
 */
public final class NodeKind {

  private NodeKind() {}

  /** 规范：方法（含构造器）。 */
  public static final String METHOD = "METHOD";
  /** 规范：被调方法调用点。 */
  public static final String METHOD_INVOCATION = "METHOD_INVOCATION";
  /** 规范：构造器调用点。 */
  public static final String NEW_CLASS = "NEW_CLASS";
  /** 规范：while 循环。 */
  public static final String WHILE_LOOP = "WHILE_LOOP";
  /** 规范：for 循环。 */
  public static final String FOR_LOOP = "FOR_LOOP";
  /** 规范：do-while 循环。 */
  public static final String DO_WHILE_LOOP = "DO_WHILE_LOOP";
  /** 规范：成员访问。 */
  public static final String MEMBER_SELECT = "MEMBER_SELECT";
  /** 规范：数组访问。 */
  public static final String ARRAY_ACCESS = "ARRAY_ACCESS";
  /** 规范：整数字面量。 */
  public static final String INT_LITERAL = "INT_LITERAL";
  /** 规范：浮点字面量。 */
  public static final String FLOAT_LITERAL = "FLOAT_LITERAL";
  /** 规范：布尔字面量。 */
  public static final String BOOLEAN_LITERAL = "BOOLEAN_LITERAL";
  /** 规范：字符字面量。 */
  public static final String CHAR_LITERAL = "CHAR_LITERAL";
  /** 规范：字符串字面量。 */
  public static final String STRING_LITERAL = "STRING_LITERAL";
  /** 规范：null 字面量。 */
  public static final String NULL_LITERAL = "NULL_LITERAL";

  /** Kotlin 原生 kind → 规范 kind（Java 名与未知名不在表中，自然透传）。 */
  private static final Map<String, String> KOTLIN_CANON =
      Map.ofEntries(
          Map.entry("FUN", METHOD),
          Map.entry("PRIMARY_CONSTRUCTOR", METHOD),
          Map.entry("SECONDARY_CONSTRUCTOR", METHOD),
          Map.entry("WHILE", WHILE_LOOP),
          Map.entry("FOR", FOR_LOOP),
          Map.entry("DO_WHILE", DO_WHILE_LOOP),
          Map.entry("CALL_EXPRESSION", METHOD_INVOCATION),
          Map.entry("CONSTRUCTOR_CALL", NEW_CLASS),
          Map.entry("DOT_QUALIFIED_EXPRESSION", MEMBER_SELECT),
          Map.entry("SAFE_ACCESS_EXPRESSION", MEMBER_SELECT),
          Map.entry("ARRAY_ACCESS_EXPRESSION", ARRAY_ACCESS),
          Map.entry("INTEGER_CONSTANT", INT_LITERAL),
          Map.entry("REAL_CONSTANT", FLOAT_LITERAL),
          Map.entry("BOOLEAN_CONSTANT", BOOLEAN_LITERAL),
          Map.entry("CHARACTER_CONSTANT", CHAR_LITERAL),
          Map.entry("STRING_TEMPLATE", STRING_LITERAL),
          Map.entry("NULL", NULL_LITERAL));

  /** 把某语言原生的节点 kind 归一成规范词表名；未知 / 已是规范名时原样返回。 */
  public static String canonical(String kind) {
    if (kind == null) return null;
    return KOTLIN_CANON.getOrDefault(kind, kind);
  }
}

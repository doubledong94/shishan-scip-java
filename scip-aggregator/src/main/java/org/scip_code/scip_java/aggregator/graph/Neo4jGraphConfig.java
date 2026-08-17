package org.scip_code.scip_java.aggregator.graph;

/**
 * Neo4j connection configuration, read from the environment so the fork stays independently
 * testable (point it at any throwaway Neo4j instance). When {@link #enabled} is false the
 * aggregation falls back to writing only the SCIP index.
 */
public final class Neo4jGraphConfig {

  public static final String ENV_URI = "NEO4J_URI";
  public static final String ENV_USER = "NEO4J_USER";
  public static final String ENV_PASSWORD = "NEO4J_PASSWORD";
  public static final String ENV_DATABASE = "NEO4J_DATABASE";

  public final String uri;
  public final String user;
  public final String password;
  public final String database;
  public final boolean enabled;

  private Neo4jGraphConfig(
      String uri, String user, String password, String database, boolean enabled) {
    this.uri = uri;
    this.user = user;
    this.password = password;
    this.database = database;
    this.enabled = enabled;
  }

  public static Neo4jGraphConfig fromEnv() {
    String uri = env(ENV_URI);
    String user = env(ENV_USER);
    String password = env(ENV_PASSWORD);
    String database = env(ENV_DATABASE);
    boolean enabled = !uri.isEmpty() && !password.isEmpty();
    return new Neo4jGraphConfig(uri, user, password, database, enabled);
  }

  private static String env(String name) {
    String value = System.getenv(name);
    return value == null ? "" : value.trim();
  }
}

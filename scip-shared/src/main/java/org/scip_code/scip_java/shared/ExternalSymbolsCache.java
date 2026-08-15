package org.scip_code.scip_java.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.scip_code.scip.SymbolInformation;

/**
 * Compilation-wide accumulation of {@link SymbolInformation} for symbols that the indexed sources
 * <em>reference</em> but don't necessarily define. Compiler plugins record every global, non-package
 * symbol they touch; the aggregator later subtracts the symbols actually defined by the documents
 * and emits the remainder as the SCIP index's {@code external_symbols}.
 *
 * <p>The candidate list is a superset on purpose: the "is this symbol defined in this codebase"
 * question can only be answered once all per-source shards have been produced, so filtering happens
 * at aggregation time. First-wins semantics keep a symbol's metadata stable regardless of how many
 * files reference it.
 */
public final class ExternalSymbolsCache {

  private final ConcurrentMap<String, SymbolInformation> symbols = new ConcurrentHashMap<>();

  public boolean contains(String symbol) {
    return symbols.containsKey(symbol);
  }

  public boolean isEmpty() {
    return symbols.isEmpty();
  }

  public void add(SymbolInformation info) {
    if (info.getSymbol().isEmpty()) return;
    symbols.putIfAbsent(info.getSymbol(), info);
  }

  public void clear() {
    symbols.clear();
  }

  /** Returns the recorded symbol information, ordered by symbol for deterministic output. */
  public List<SymbolInformation> symbols() {
    List<SymbolInformation> result = new ArrayList<>(symbols.values());
    result.sort((a, b) -> a.getSymbol().compareTo(b.getSymbol()));
    return result;
  }
}
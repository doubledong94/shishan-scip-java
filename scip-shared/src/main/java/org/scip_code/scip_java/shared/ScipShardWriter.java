package org.scip_code.scip_java.shared;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.SymbolInformation;

/** Serializes a single SCIP {@link Document} as a singleton {@link Index} on disk. */
public final class ScipShardWriter {
  private ScipShardWriter() {}

  /**
   * Wraps {@code document} in a singleton {@link Index} message (no {@code Metadata}) and writes it
   * to {@code output}, creating parent directories as needed. The aggregator owns the per-index
   * metadata, so per-source shards intentionally omit it.
   */
  public static void writeShard(Path output, Document document) throws IOException {
    writeShard(output, Index.newBuilder().addDocuments(document).build());
  }

  /**
   * Writes {@code document} together with the compilation's candidate external symbols into a
   * singleton {@link Index}. The aggregator filters these candidates against the symbols actually
   * defined by the documents and emits the remainder as {@code external_symbols}.
   */
  public static void writeShard(
      Path output, Document document, List<SymbolInformation> externalSymbols) throws IOException {
    Index.Builder builder = Index.newBuilder().addDocuments(document);
    if (!externalSymbols.isEmpty()) builder.addAllExternalSymbols(externalSymbols);
    writeShard(output, builder.build());
  }

  private static void writeShard(Path output, Index index) throws IOException {
    byte[] bytes = index.toByteArray();
    Files.createDirectories(output.getParent());
    Files.write(output, bytes);
  }
}

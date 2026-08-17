package org.scip_code.scip_java.aggregator;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.ProtocolVersion;
import org.scip_code.scip.Relationship;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SyntaxKind;
import org.scip_code.scip.TextEncoding;
import org.scip_code.scip.ToolInfo;
import org.scip_code.scip_java.shared.ScipSymbols;
import org.scip_code.scip_java.shared.SyntaxTree;
import org.scip_code.scip_java.aggregator.graph.GraphExtractor;
import org.scip_code.scip_java.aggregator.graph.Neo4jGraphConfig;
import org.scip_code.scip_java.aggregator.graph.Neo4jGraphWriter;

/**
 * Aggregates per-source SCIP shards (one {@link Index} per {@code *.scip} file emitted by the
 * compiler plugins) into a single SCIP index. The aggregator:
 *
 * <ul>
 *   <li>discovers shards under each requested target root,
 *   <li>resolves each shard's bare-descriptor symbols to fully-qualified SCIP symbols using a
 *       {@link PackageTable},
 *   <li>optionally adds inverse-reference relationships across documents, and
 *   <li>emits a single {@link Index} with leading {@link Metadata}.
 * </ul>
 *
 * <p>In addition, each source file's {@code *.tree} sidecar (the syntax tree that replaced the flat
 * {@code occurrences} list) is read, its node symbols rewritten, and all trees merged into a single
 * {@code *.tree.json} output next to the index.
 */
public class ScipAggregator {
  private static final PathMatcher JAR_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.jar");
  private static final PathMatcher SCIP_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.scip");
  private static final PathMatcher TREE_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.tree");

  private final ScipWriter writer;
  private final ScipAggregatorOptions options;
  private final List<Struct> mergedTrees = new ArrayList<>();
  private final Map<String, SymbolInformation> collectedSymbols = new HashMap<>();
  private final Map<String, SyntaxTree.Node> mergedTreeNodes = new HashMap<>();

  public ScipAggregator(ScipWriter writer, ScipAggregatorOptions options) {
    this.writer = writer;
    this.options = options;
  }

  public static void run(ScipAggregatorOptions options) throws IOException {
    ScipWriter writer = new ScipWriter(options);
    new ScipAggregator(writer, options).run();
  }

  private void run() throws IOException {
    PackageTable packages = new PackageTable(options);
    SymbolRewriter rewriter = new SymbolRewriter(packages);
    List<Path> shards = findShards();
    Collections.sort(shards);
    if (options.reporter().hasErrors()) return;
    if (shards.isEmpty() && !options.allowEmptyIndex()) {
      options
          .reporter()
          .error(
              "No SCIP shards found. This typically means that `scip-java` is unable to"
                  + " automatically index this codebase. If you are using Gradle or Maven, please"
                  + " report an issue to https://github.com/scip-code/scip-java and include steps"
                  + " to reproduce. If you are using a different build tool, make sure that you"
                  + " have followed all of the manual configuration steps.");
      return;
    }
    options.reporter().startProcessing(shards.size());
    writer.emitTyped(metadataIndex());

    Map<String, List<String>> inverseReferences = computeInverseReferences(shards, rewriter);
    Map<String, SymbolInformation> externalCandidates = new ConcurrentHashMap<>();
    Set<String> definedSymbols = ConcurrentHashMap.newKeySet();
    shardStream(shards)
        .forEach(shard -> processShard(shard, rewriter, inverseReferences, externalCandidates, definedSymbols));
    emitExternalSymbols(externalCandidates, definedSymbols);
    emitMergedTrees(rewriter);
    emitGraph();
    writer.build();
    options.reporter().endProcessing();
  }

  /**
   * Returns every {@code *.scip} shard under {@code options.targetroots}. A targetroot that happens
   * to be a {@code .jar} is included as-is so callers can pick out shards stored inside.
   */
  private List<Path> findShards() throws IOException {
    List<Path> shards = new ArrayList<>();
    SimpleFileVisitor<Path> visitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (SCIP_PATTERN.matches(file)) shards.add(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            options.reporter().error(exc);
            return FileVisitResult.CONTINUE;
          }
        };
    for (Path root : options.targetroots()) {
      if (JAR_PATTERN.matches(root)) shards.add(root);
      else if (Files.isDirectory(root)) Files.walkFileTree(root, visitor);
      else
        options
            .reporter()
            .warning("ignoring target root that does not exist or is not a directory: " + root);
    }
    return shards;
  }

  /**
   * Finds every {@code *.tree} sidecar under {@code options.targetroots()} and merges it into the
   * output tree, rewriting node symbols with {@code rewriter}.
   */
  private void emitMergedTrees(SymbolRewriter rewriter) throws IOException {
    List<Path> trees = new ArrayList<>();
    SimpleFileVisitor<Path> visitor =
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (TREE_PATTERN.matches(file)) trees.add(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            options.reporter().error(exc);
            return FileVisitResult.CONTINUE;
          }
        };
    for (Path root : options.targetroots()) {
      if (Files.isDirectory(root)) Files.walkFileTree(root, visitor);
    }
    if (trees.isEmpty()) return;
    Collections.sort(trees);

    Struct.Builder documents = Struct.newBuilder();
    for (Path treePath : trees) {
      try {
        com.google.protobuf.CodedInputStream input =
            com.google.protobuf.CodedInputStream.newInstance(Files.readAllBytes(treePath));
        input.setRecursionLimit(1_000_000);
        Struct documentStruct = Struct.parseFrom(input);
        String relativePath = SyntaxTree.documentRelativePath(documentStruct);
        if (relativePath.isEmpty()) continue;
        SyntaxTree.Node node = SyntaxTree.documentTree(documentStruct);
        if (node == null) continue;
        SyntaxTree.Node rewritten =
            SyntaxTree.rewriteSymbols(node, symbol -> rewriter.rewrite(symbol));
        mergedTreeNodes.put(relativePath, rewritten);
        documents.putFields(
            relativePath,
            Value.newBuilder()
                .setStructValue(SyntaxTree.toStruct(rewritten))
                .build());
      } catch (IOException e) {
        options.reporter().error("invalid SCIP tree sidecar: " + treePath);
        options.reporter().error(e);
      }
    }
    Path treeOutput = treeOutputPath();
    byte[] bytes = documents.build().toByteArray();
    Files.createDirectories(treeOutput.getParent());
    Files.write(treeOutput, bytes);
  }

  private Path treeOutputPath() {
    Path output = options.output();
    String name = output.getFileName().toString();
    String treeName = name.endsWith(".scip") ? name.substring(0, name.length() - ".scip".length()) : name;
    return output.resolveSibling(treeName + ".tree");
  }

  /**
   * Streams the code graph into Neo4j (aggregation phase). Driven entirely by the environment
   * ({@code NEO4J_URI} / {@code NEO4J_USER} / {@code NEO4J_PASSWORD}); when not configured the
   * aggregator just produces the SCIP index as before.
   */
  private void emitGraph() {
    Neo4jGraphConfig config = Neo4jGraphConfig.fromEnv();
    if (!config.enabled) return;
    // Explicit project name (gateway sets SCIP_PROJECT_NAME because the working dir is a
    // per-project copy whose basename differs from the project name); fall back to the
    // sourceroot basename when running standalone.
    String project = System.getenv("SCIP_PROJECT_NAME");
    if (project == null || project.isEmpty()) {
      project = options.sourceroot().getFileName().toString();
    }
    if (project.isEmpty()) {
      options.reporter().error("cannot derive project name from sourceroot: " + options.sourceroot());
      return;
    }
    try (Neo4jGraphWriter graph = new Neo4jGraphWriter(config, project)) {
      graph.deleteProject();
      graph.ensureSchema();
      GraphExtractor extractor = new GraphExtractor(graph, project, collectedSymbols);
      for (Map.Entry<String, SyntaxTree.Node> entry : mergedTreeNodes.entrySet()) {
        extractor.extractFile(entry.getKey(), entry.getValue());
      }
      extractor.emitRelationships();
      graph.flush();
      options.reporter().info("wrote code graph to Neo4j for project " + project);
    } catch (Exception e) {
      options.reporter().error("Neo4j graph write failed: " + e);
      if (e.getStackTrace().length > 0) {
        options.reporter().error(e.getStackTrace()[0].toString());
      }
    }
  }

  private Index metadataIndex() {
    return Index.newBuilder()
        .setMetadata(
            Metadata.newBuilder()
                .setVersion(ProtocolVersion.UnspecifiedProtocolVersion)
                .setProjectRoot(options.sourceroot().toUri().toString())
                .setTextDocumentEncoding(TextEncoding.UTF8)
                .setToolInfo(
                    ToolInfo.newBuilder()
                        .setName(options.toolInfo().getName())
                        .setVersion(options.toolInfo().getVersion())
                        .addAllArguments(options.toolInfo().getArgumentsList())))
        .build();
  }

  private void processShard(
      Path shardPath,
      SymbolRewriter rewriter,
      Map<String, List<String>> inverseReferences,
      Map<String, SymbolInformation> externalCandidates,
      Set<String> definedSymbols) {
    for (Index shardIndex : readShards(shardPath)) {
      for (Document shard : shardIndex.getDocumentsList()) {
        Document rewritten = rewriteDocument(shard, rewriter, inverseReferences);
        writer.emitTyped(Index.newBuilder().addDocuments(rewritten).build());
        options.reporter().processedOneItem();
        for (SymbolInformation info : rewritten.getSymbolsList()) {
          if (!info.getSymbol().isEmpty()) definedSymbols.add(info.getSymbol());
          if (!info.getSymbol().isEmpty()) collectedSymbols.put(info.getSymbol(), info);
        }
      }
      for (SymbolInformation info : shardIndex.getExternalSymbolsList()) {
        String rewritten = rewriter.rewrite(info.getSymbol());
        if (rewritten.isEmpty()) continue;
        externalCandidates.putIfAbsent(rewritten, rebuildExternal(rewritten, info));
      }
    }
  }

  /**
   * Emits the aggregated {@code external_symbols}: every candidate external symbol the shards
   * referenced that none of the documents actually defines, excluding local symbols and bare package
   * paths. Unknown fields (relationships, enclosing symbol) are intentionally dropped because they
   * would reference symbols whose package context the aggregator can't reliably infer.
   */
  private void emitExternalSymbols(
      Map<String, SymbolInformation> candidates, Set<String> definedSymbols) {
    if (candidates.isEmpty()) return;
    List<SymbolInformation> externals = new ArrayList<>();
    for (Map.Entry<String, SymbolInformation> entry : candidates.entrySet()) {
      String symbol = entry.getKey();
      if (definedSymbols.contains(symbol)) continue;
      if (ScipSymbols.isLocal(symbol)) continue;
      if (symbol.endsWith("/")) continue;
      externals.add(entry.getValue());
    }
    if (externals.isEmpty()) return;
    externals.sort(Comparator.comparing(SymbolInformation::getSymbol));
    writer.emitTyped(Index.newBuilder().addAllExternalSymbols(externals).build());
  }

  private static SymbolInformation rebuildExternal(String symbol, SymbolInformation info) {
    SymbolInformation.Builder builder =
        SymbolInformation.newBuilder()
            .setSymbol(symbol)
            .setDisplayName(info.getDisplayName())
            .setKind(info.getKind());
    if (info.hasSignatureDocumentation()) {
      builder.setSignatureDocumentation(info.getSignatureDocumentation());
    }
    builder.addAllDocumentation(info.getDocumentationList());
    return builder.build();
  }

  private Document rewriteDocument(
      Document shard, SymbolRewriter rewriter, Map<String, List<String>> inverseReferences) {
    Document.Builder out =
        Document.newBuilder()
            .setLanguage(shard.getLanguage())
            .setRelativePath(shard.getRelativePath());
    if (!shard.getText().isEmpty()) out.setText(shard.getText());

    for (Occurrence occ : shard.getOccurrencesList()) {
      Occurrence.Builder rebuilt =
          Occurrence.newBuilder()
              .setSymbol(rewriter.rewrite(occ.getSymbol()))
              .setSymbolRoles(occ.getSymbolRoles());
      if (occ.getSyntaxKind() != SyntaxKind.UnspecifiedSyntaxKind) {
        rebuilt.setSyntaxKind(occ.getSyntaxKind());
      }
      switch (occ.getTypedRangeCase()) {
        case SINGLE_LINE_RANGE -> rebuilt.setSingleLineRange(occ.getSingleLineRange());
        case MULTI_LINE_RANGE -> rebuilt.setMultiLineRange(occ.getMultiLineRange());
        case TYPEDRANGE_NOT_SET ->
            throw new IllegalArgumentException("expected SCIP 0.9 typed occurrence range");
      }
      switch (occ.getTypedEnclosingRangeCase()) {
        case SINGLE_LINE_ENCLOSING_RANGE ->
            rebuilt.setSingleLineEnclosingRange(occ.getSingleLineEnclosingRange());
        case MULTI_LINE_ENCLOSING_RANGE ->
            rebuilt.setMultiLineEnclosingRange(occ.getMultiLineEnclosingRange());
        case TYPEDENCLOSINGRANGE_NOT_SET -> {}
      }
      out.addOccurrences(rebuilt);
    }

    for (SymbolInformation info : shard.getSymbolsList()) {
      SymbolInformation.Builder rebuilt =
          SymbolInformation.newBuilder()
              .setSymbol(rewriter.rewrite(info.getSymbol()))
              .setDisplayName(info.getDisplayName())
              .setKind(info.getKind());
      if (info.hasSignatureDocumentation()) {
        rebuilt.setSignatureDocumentation(info.getSignatureDocumentation());
      }
      if (!info.getEnclosingSymbol().isEmpty()) {
        rebuilt.setEnclosingSymbol(rewriter.rewrite(info.getEnclosingSymbol()));
      }
      for (String doc : info.getDocumentationList()) rebuilt.addDocumentation(doc);
      for (Relationship rel : info.getRelationshipsList()) {
        rebuilt.addRelationships(
            Relationship.newBuilder(rel).setSymbol(rewriter.rewrite(rel.getSymbol())));
      }
      List<String> inverse = inverseReferences.get(info.getSymbol());
      if (inverse != null) {
        for (String overrider : inverse) {
          rebuilt.addRelationships(
              Relationship.newBuilder()
                  .setSymbol(rewriter.rewrite(overrider))
                  .setIsImplementation(true)
                  .setIsReference(true));
        }
      }
      out.addSymbols(rebuilt);
    }
    return out.build();
  }

  /**
   * Builds {@code overridden-symbol → [overriding-symbols]} for every method-style relationship.
   * Class/interface parent relationships are excluded because they shouldn't surface as
   * find-references results (TODO: drop once sourcegraph#50927 is fixed).
   */
  private Map<String, List<String>> computeInverseReferences(
      List<Path> shards, SymbolRewriter rewriter) {
    if (!options.emitInverseRelationships()) return Collections.emptyMap();
    Map<String, List<String>> result = new HashMap<>();
    for (Path shard : shards) {
      for (Index shardIndex : readShards(shard)) {
        for (Document doc : shardIndex.getDocumentsList()) {
          for (SymbolInformation info : doc.getSymbolsList()) {
            if (!supportsReferenceRelationship(info)) continue;
            if (info.getSymbol().isEmpty() || ScipSymbols.isLocal(info.getSymbol())) continue;
            for (Relationship rel : info.getRelationshipsList()) {
              if (!rel.getIsImplementation()) continue;
              if (ScipSymbols.isLocal(rel.getSymbol())) continue;
              if (isIgnoredOverriddenSymbol(rel.getSymbol())) continue;
              result.computeIfAbsent(rel.getSymbol(), k -> new ArrayList<>()).add(info.getSymbol());
            }
          }
        }
      }
    }
    return result;
  }

  private static boolean supportsReferenceRelationship(SymbolInformation info) {
    return switch (info.getKind()) {
      case Class, Enum, Interface, Type, Object, PackageObject -> false;
      default -> true;
    };
  }

  private static boolean isIgnoredOverriddenSymbol(String symbol) {
    // Skip java/lang/Object# from cross-shard reference relationships; it's the parent
    // of every class and would dominate "find implementations" results.
    return symbol.endsWith("java/lang/Object#");
  }

  private Stream<Path> shardStream(List<Path> shards) {
    return options.parallel() ? shards.parallelStream() : shards.stream();
  }

  private Collection<Index> readShards(Path shardPath) {
    try {
      if (JAR_PATTERN.matches(shardPath)) return readShardsFromJar(shardPath);
      return List.of(Index.parseFrom(parseFromBytes(Files.readAllBytes(shardPath))));
    } catch (IOException e) {
      options.reporter().error("invalid SCIP shard: " + shardPath);
      options.reporter().error(e);
      return Collections.emptyList();
    }
  }

  private Collection<Index> readShardsFromJar(Path jarFile) throws IOException {
    List<Index> result = new ArrayList<>();
    try (JarFile jar = new JarFile(jarFile.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.getName().endsWith(".scip")) continue;
        byte[] bytes = InputStreamBytes.readAll(jar.getInputStream(entry));
        result.add(Index.parseFrom(parseFromBytes(bytes)));
      }
    }
    return result;
  }

  private static CodedInputStream parseFromBytes(byte[] bytes) {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    in.setRecursionLimit(1000);
    return in;
  }
}

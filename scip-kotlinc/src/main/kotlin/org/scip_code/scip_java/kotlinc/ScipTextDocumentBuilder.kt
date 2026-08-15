package org.scip_code.scip_java.kotlinc

import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.KtSourceFile
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirPackageDirective
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenSymbolsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.toClassLikeSymbol
import org.jetbrains.kotlin.fir.analysis.getChild
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.renderer.*
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.impl.FirImplicitAnyTypeRef
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.text
import org.scip_code.scip.Document
import org.scip_code.scip.SymbolInformation
import org.scip_code.scip.SymbolInformation.Kind
import org.scip_code.scip.SymbolRole
import org.scip_code.scip.SyntaxKind
import org.scip_code.scip.relationship
import org.scip_code.scip.signature
import org.scip_code.scip.symbolInformation
import org.scip_code.scip_java.shared.ExternalSymbolsCache
import org.scip_code.scip_java.shared.ScipDocumentBuilder
import org.scip_code.scip_java.shared.ScipRange
import org.scip_code.scip_java.shared.ScipShardPaths
import org.scip_code.scip_java.shared.SyntaxTree

/** Builds a SCIP [Document] (symbols only) and a full [SyntaxTree] for a single Kotlin source file. */
class ScipTextDocumentBuilder(
    private val sourceroot: Path,
    private val file: KtSourceFile,
    private val lineMap: LineMap,
    private val cache: SymbolsCache,
    private val externals: ExternalSymbolsCache,
    private val fileRoot: KtSourceElement?,
) {
    private val documentBuilder = ScipDocumentBuilder()
    private val fileText = file.getContentsAsStream().reader().readText()
    private val occurrences: MutableMap<Long, MutableList<SyntaxTree.OccurrenceData>> = HashMap()
    private val nodesByOffset: MutableMap<Long, MutableList<SyntaxTree.Node>> = HashMap()
    private var treeRoot: SyntaxTree.Node? = null

    fun build(): Document = documentBuilder.build("kotlin", relativePath(), fileText)

    /** The full per-file syntax tree, built once on first access. */
    fun tree(): SyntaxTree.Node {
        if (treeRoot == null) {
            treeRoot = buildTree()
            attachOccurrences()
        }
        return treeRoot!!
    }

    context(context: CheckerContext)
    fun emitScipData(
        firBasedSymbol: FirBasedSymbol<*>?,
        symbol: Symbol,
        element: KtSourceElement,
        isDefinition: Boolean,
        enclosingSource: KtSourceElement? = null,
    ) {
        val key = offsetKey(element.startOffset, element.endOffset)
        val occurrence = SyntaxTree.OccurrenceData()
        occurrence.symbol = symbol.toString()
        occurrence.role = if (isDefinition) SymbolRole.Definition.number else 0
        val syntaxKind = syntaxKind(firBasedSymbol, symbol, element, isDefinition)
        if (syntaxKind != SyntaxKind.UnspecifiedSyntaxKind) occurrence.syntaxKind = syntaxKind.name
        occurrence.range = range(element)
        if (enclosingSource != null) occurrence.enclosingRange = enclosingRange(enclosingSource)
        occurrences.computeIfAbsent(key) { mutableListOf() }.add(occurrence)
        if (isDefinition) {
            documentBuilder.addSymbol(symbolInformation(firBasedSymbol, symbol, element))
        }
        recordExternalCandidate(firBasedSymbol, symbol)
    }

    @OptIn(SymbolInternals::class)
    context(context: CheckerContext)
    private fun symbolInformation(
        firBasedSymbol: FirBasedSymbol<*>?,
        symbol: Symbol,
        element: KtSourceElement,
    ): SymbolInformation {
        val supers =
            when (firBasedSymbol) {
                is FirClassSymbol ->
                    firBasedSymbol.resolvedSuperTypeRefs
                        .filter { it !is FirImplicitAnyTypeRef }
                        .mapNotNull { it.toClassLikeSymbol(firBasedSymbol.moduleData.session) }
                        .flatMap { cache[it] }
                is FirFunctionSymbol<*> ->
                    firBasedSymbol.directOverriddenSymbolsSafe().flatMap { cache[it] }
                else -> emptyList()
            }
        return symbolInformation {
            this.symbol = symbol.toString()
            this.displayName =
                if (firBasedSymbol != null) displayName(firBasedSymbol) else element.text.toString()
            if (firBasedSymbol != null) {
                renderSignature(firBasedSymbol.fir)?.let { rendered ->
                    signatureDocumentation = signature {
                        language = "kotlin"
                        text = rendered
                    }
                }
                docComment(firBasedSymbol.fir)?.let { documentation += it }
            }
            this.kind = scipKind(firBasedSymbol?.fir)
            this.enclosingSymbol =
                context.containingDeclarations.lastOrNull()?.let { cache[it].last().toString() }
                    ?: ""
            for (parent in supers) {
                relationships += relationship {
                    this.symbol = parent.toString()
                    isImplementation = true
                }
            }
        }
    }

    /**
     * Records [firBasedSymbol] as an external-symbol candidate unless it is local or a package
     * path. The aggregator subtracts symbols the codebase itself defines, so this is intentionally
     * called for every global symbol encountered (definitions and references alike).
     */
    @OptIn(SymbolInternals::class)
    private fun recordExternalCandidate(firBasedSymbol: FirBasedSymbol<*>?, symbol: Symbol) {
        if (firBasedSymbol == null) return
        val symbolString = symbol.toString()
        if (symbolString.isEmpty() || symbolString.endsWith("/") || symbol.isLocal()) return
        if (externals.contains(symbolString)) return
        externals.add(
            symbolInformation {
                this.symbol = symbolString
                displayName = displayName(firBasedSymbol)
                renderSignature(firBasedSymbol.fir)?.let {
                    signatureDocumentation = signature {
                        language = "kotlin"
                        text = it
                    }
                }
                docComment(firBasedSymbol.fir)?.let { documentation += it }
                this.kind = scipKind(firBasedSymbol.fir)
            },
        )
    }

    // =======================================
    // Syntax tree
    // =======================================

    private fun buildTree(): SyntaxTree.Node {
        val root = fileRoot ?: return SyntaxTree.Node("FILE")
        val structure = root.treeStructure
        val node = root.lighterASTNode
        return buildNode(node, structure)
    }

    private fun buildNode(
        node: LighterASTNode,
        structure: org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure<LighterASTNode>,
    ): SyntaxTree.Node {
        val treeNode = SyntaxTree.Node(kindName(node))
        treeNode.range = rangeForOffsets(node.startOffset, node.endOffset)
        nodesByOffset.computeIfAbsent(offsetKey(node.startOffset, node.endOffset)) { mutableListOf() }
            .add(treeNode)
        val ref = Ref<Array<LighterASTNode>>()
        val childCount = structure.getChildren(node, ref)
        val children = ref.get()
        if (children != null) {
            for (i in 0 until childCount) {
                treeNode.children.add(buildNode(children[i], structure))
            }
        }
        structure.disposeChildren(children, childCount)
        return treeNode
    }

    /**
     * Attaches the resolved-symbol data recorded by [emitScipData] to the tree node(s) whose
     * offsets match the source element. Preorder construction means the last node registered for a
     * given offset pair is the deepest one (e.g. an identifier token inside a composite node).
     */
    private fun attachOccurrences() {
        for ((key, datas) in occurrences) {
            val candidates = nodesByOffset[key] ?: continue
            val node = candidates.last()
            node.occurrences.addAll(datas)
        }
    }

    private fun kindName(node: LighterASTNode): String =
        node.tokenType?.toString() ?: "UNKNOWN"

    private fun offsetKey(start: Int, end: Int): Long =
        (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

    private fun rangeForOffsets(start: Int, end: Int): ScipRange {
        val startLine = lineMap.lineNumberForOffset(start) - 1
        val startCol = lineMap.columnForOffset(start)
        val endLine = lineMap.lineNumberForOffset(end) - 1
        val endCol = lineMap.columnForOffset(end)
        return ScipRange.range(startLine, startCol, endLine, endCol)
    }

    private fun range(element: KtSourceElement): ScipRange {
        val line = lineMap.lineNumber(element) - 1
        val startCol = lineMap.startCharacter(element)
        val endCol = lineMap.endCharacter(element)
        return ScipRange.singleLine(line, startCol, endCol)
    }

    private fun enclosingRange(element: KtSourceElement): ScipRange {
        val startLine = lineMap.lineNumber(element) - 1
        val startCol = lineMap.startCharacter(element)
        val endLine = lineMap.lineNumberForOffset(element.endOffset) - 1
        val endCol = lineMap.columnForOffset(element.endOffset)
        return ScipRange.range(startLine, startCol, endLine, endCol)
    }

    private fun relativePath(): String =
        ScipShardPaths.relativePath(sourceroot, Paths.get(file.path))

    /**
     * Renders [element] as a Kotlin signature using [FirRenderer]'s readability preset, with kdoc
     * stripped (kdoc is exposed separately via [SymbolInformation.documentation]).
     */
    private fun renderSignature(element: FirElement): String? {
        val renderer =
            FirRenderer(
                typeRenderer = ConeTypeRenderer(),
                idRenderer = ConeIdShortRenderer(),
                classMemberRenderer = FirNoClassMemberRenderer(),
                bodyRenderer = null,
                propertyAccessorRenderer = null,
                callArgumentsRenderer = FirCallNoArgumentsRenderer(),
                modifierRenderer = FirAllModifierRenderer(FirModifierRenderer.StaticPolicy.Default),
                callableSignatureRenderer = FirCallableSignatureRendererForReadability(),
                declarationRenderer = FirDeclarationRenderer("local "),
            )
        val rendered = renderer.renderElementAsString(element)
        return if (rendered.isEmpty()) null else rendered
    }

    private fun docComment(element: FirElement): String? {
        val kdoc = element.source?.getChild(KtTokens.DOC_COMMENT)?.text?.toString() ?: return null
        return stripKdoc(kdoc).ifEmpty { null }
    }

    private fun scipKind(element: FirElement?): Kind =
        when (element) {
            is FirClass if element.isInterface -> Kind.Interface
            is FirTypeAlias -> Kind.TypeAlias
            is FirClassLikeDeclaration -> Kind.Class
            is FirConstructor -> Kind.Constructor
            is FirTypeParameter -> Kind.TypeParameter
            is FirValueParameter -> Kind.Parameter
            is FirField -> Kind.Field
            is FirProperty -> Kind.Property
            is FirEnumEntry -> Kind.EnumMember
            is FirVariable -> Kind.Variable
            is FirCallableDeclaration -> Kind.Method
            is FirPackageDirective -> Kind.Package
            else -> Kind.UNRECOGNIZED
        }

    /** Classifies an occurrence's token, for editors that render it by [SyntaxKind]. */
    private fun syntaxKind(
        firBasedSymbol: FirBasedSymbol<*>?,
        symbol: Symbol,
        element: KtSourceElement,
        isDefinition: Boolean,
    ): SyntaxKind {
        val text = element.text?.toString()
        if (text == "this" || text == "super") return SyntaxKind.Keyword
        return when (firBasedSymbol) {
            is FirValueParameterSymbol -> SyntaxKind.IdentifierParameter
            is FirTypeParameterSymbol -> SyntaxKind.IdentifierType
            is FirClassLikeSymbol -> SyntaxKind.IdentifierType
            is FirFunctionSymbol<*>, is FirPropertyAccessorSymbol ->
                if (isDefinition) SyntaxKind.IdentifierFunctionDefinition
                else SyntaxKind.IdentifierFunction
            is FirPropertySymbol, is FirVariableSymbol ->
                if (symbol.isLocal()) SyntaxKind.IdentifierLocal else SyntaxKind.Identifier
            null ->
                if (symbol.toString().endsWith("/")) SyntaxKind.IdentifierNamespace
                else SyntaxKind.UnspecifiedSyntaxKind
            else -> SyntaxKind.UnspecifiedSyntaxKind
        }
    }

    /** Strips the `/**`, leading `*`s, and `*/` from a kdoc block, returning just the body text. */
    private fun stripKdoc(kdoc: String): String {
        if (kdoc.isEmpty()) return kdoc
        val out = StringBuilder()
        var first = true
        kdoc.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            var start = 0
            while (start < line.length && line[start].isWhitespace()) start++
            if (start < line.length && line[start] == '/') start++
            while (start < line.length && line[start] == '*') start++
            var end = line.length - 1
            if (end > start && line[end] == '/') end--
            while (end > start && line[end] == '*') end--
            while (end > start && line[end].isWhitespace()) end--
            start = minOf(start, line.length - 1)
            if (end > start) end++
            if (!first) out.append('\n')
            out.append(line, start, end)
            first = false
        }
        return out.toString().trim()
    }

    companion object {
        @OptIn(SymbolInternals::class, RenderingInternals::class)
        private fun displayName(firBasedSymbol: FirBasedSymbol<*>): String =
            when (firBasedSymbol) {
                is FirClassLikeSymbol -> firBasedSymbol.classId.shortClassName.asString()
                is FirPropertyAccessorSymbol -> firBasedSymbol.fir.propertySymbol.name.asString()
                is FirFunctionSymbol -> firBasedSymbol.callableId.callableName.asString()
                is FirPropertySymbol ->
                    firBasedSymbol.callableIdForRendering.callableName.asString()
                is FirVariableSymbol -> firBasedSymbol.name.asString()
                is FirTypeParameterSymbol -> firBasedSymbol.name.asString()
                else -> firBasedSymbol.toString()
            }
    }
}

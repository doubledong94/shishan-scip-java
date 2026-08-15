package org.scip_code.scip_java.kotlinc.test

import org.scip_code.scip.Occurrence
import org.scip_code.scip.SymbolInformation
import org.scip_code.scip.SymbolRole
import org.scip_code.scip.SyntaxKind
import org.scip_code.scip.relationship
import org.scip_code.scip.signature
import org.scip_code.scip.symbolInformation
import org.scip_code.scip_java.shared.ScipRange

/**
 * Tiny DSL for building SCIP [Occurrence] / [SymbolInformation] test fixtures with the same shape
 * as the original SCIP-based one used by the Kotlin tests.
 *
 * <p>Example:
 * ```
 * scipOccurrence {
 *     role = DEFINITION
 *     symbol = "sample/Banana#"
 *     range { startLine = 1; startCharacter = 6; endLine = 1; endCharacter = 12 }
 *     enclosingRange { startLine = 1; endLine = 3; endCharacter = 1 }
 * }
 * ```
 */
internal val REFERENCE: Int = SymbolRole.UnspecifiedSymbolRole.number
internal val DEFINITION: Int = SymbolRole.Definition.number

@DslMarker annotation class ScipBuilderDsl

@ScipBuilderDsl
class ScipRangeBuilder {
    var startLine: Int = 0
    var startCharacter: Int = 0
    /**
     * Default sentinel: when [endLine] is left untouched, the produced range is single-line at
     * [startLine].
     */
    var endLine: Int = -1
    var endCharacter: Int = 0

    internal fun toScipRange(): ScipRange {
        val line = if (endLine < 0) startLine else endLine
        return ScipRange.range(startLine, startCharacter, line, endCharacter)
    }
}

@ScipBuilderDsl
class ScipOccurrenceBuilder {
    var role: Int = REFERENCE
    var symbol: String = ""
    /**
     * Optional explicit [SyntaxKind]. When unset, [deriveSyntaxKind] infers it from [symbol] and
     * [role], so existing fixtures keep passing without listing it on every occurrence. `local N`
     * symbols are ambiguous and must set this explicitly.
     */
    var syntaxKind: SyntaxKind? = null
    private var range: ScipRange? = null
    private var enclosingRange: ScipRange? = null

    fun range(block: ScipRangeBuilder.() -> Unit) {
        range = ScipRangeBuilder().apply(block).toScipRange()
    }

    fun enclosingRange(block: ScipRangeBuilder.() -> Unit) {
        enclosingRange = ScipRangeBuilder().apply(block).toScipRange()
    }

    internal fun build(): Occurrence {
        val builder = Occurrence.newBuilder().setSymbol(symbol).setSymbolRoles(role)
        val kind = syntaxKind ?: deriveSyntaxKind(symbol, role)
        if (kind != SyntaxKind.UnspecifiedSyntaxKind) builder.syntaxKind = kind
        range?.let {
            if (it.isSingleLine) builder.singleLineRange = it.toSingleLineRange()
            else builder.multiLineRange = it.toMultiLineRange()
        }
        enclosingRange?.let {
            if (it.isSingleLine) builder.singleLineEnclosingRange = it.toSingleLineRange()
            else builder.multiLineEnclosingRange = it.toMultiLineRange()
        }
        return builder.build()
    }
}

/**
 * Mirrors [org.scip_code.scip_java.kotlinc.ScipTextDocumentBuilder.syntaxKind] for the symbol
 * shapes used by the fixtures: namespaces, types, functions, parameters, type parameters and
 * properties are unambiguous from the symbol string; only `local N` symbols need an explicit
 * [ScipOccurrenceBuilder.syntaxKind].
 */
internal fun deriveSyntaxKind(symbol: String, role: Int): SyntaxKind =
    when {
        symbol.startsWith("local ") -> SyntaxKind.UnspecifiedSyntaxKind
        symbol.endsWith("/") -> SyntaxKind.IdentifierNamespace
        symbol.endsWith("#") -> SyntaxKind.IdentifierType
        symbol.endsWith(").") ->
            if (role == DEFINITION) SyntaxKind.IdentifierFunctionDefinition
            else SyntaxKind.IdentifierFunction
        symbol.endsWith(")") -> SyntaxKind.IdentifierParameter
        symbol.endsWith("]") -> SyntaxKind.IdentifierType
        symbol.endsWith(".") -> SyntaxKind.Identifier
        else -> SyntaxKind.UnspecifiedSyntaxKind
    }

@ScipBuilderDsl
class ScipSymbolInformationBuilder {
    var symbol: String = ""
    var kind: SymbolInformation.Kind = SymbolInformation.Kind.UnspecifiedKind
    var enclosingSymbol: String = ""
    var displayName: String = ""
    var signatureText: String? = null
    private val docs = mutableListOf<String>()
    private val overrides = mutableListOf<String>()

    fun documentation(text: String) {
        docs += text
    }

    /**
     * Appends an `is_implementation` [Relationship]. Mirrors the old SCIP-flavored
     * `addOverriddenSymbols` so existing test fixtures port over with minimal diff.
     */
    fun addOverriddenSymbols(vararg symbols: String) {
        overrides.addAll(symbols)
    }

    internal fun build(): SymbolInformation = symbolInformation {
        symbol = this@ScipSymbolInformationBuilder.symbol
        kind = this@ScipSymbolInformationBuilder.kind
        enclosingSymbol = this@ScipSymbolInformationBuilder.enclosingSymbol
        if (this@ScipSymbolInformationBuilder.displayName.isNotEmpty()) {
            displayName = this@ScipSymbolInformationBuilder.displayName
        }
        signatureText?.let { sigText ->
            signatureDocumentation = signature {
                language = "kotlin"
                text = sigText
            }
        }
        for (d in docs) documentation += d
        for (s in overrides) {
            relationships += relationship {
                symbol = s
                isImplementation = true
            }
        }
    }
}

internal fun scipOccurrence(block: ScipOccurrenceBuilder.() -> Unit): Occurrence =
    ScipOccurrenceBuilder().apply(block).build()

internal fun scipSymbol(block: ScipSymbolInformationBuilder.() -> Unit): SymbolInformation =
    ScipSymbolInformationBuilder().apply(block).build()

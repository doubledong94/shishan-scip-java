package org.scip_code.scip_java.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import java.nio.file.Files
import java.nio.file.Path
import org.scip_code.scip.Index
import org.scip_code.scip_java.ScipJavaApp

/**
 * `scip-java dump-json`: prints a SCIP index (or a per-source shard, or a merged `.tree` sidecar)
 * as JSON so the emitted symbols/tree can be inspected by eye.
 */
class DumpCommand : CliktCommand(name = "dump-json") {

    override fun help(context: Context) =
        "Prints a SCIP index file, or a `.tree` syntax-tree sidecar, as JSON."

    private val app: ScipJavaApp by requireObject()

    private val indexFile: Path by argument(help = "Path to the .scip index, shard, or .tree file").path()

    override fun run() {
        if (!Files.exists(indexFile)) {
            app.error("index file does not exist: $indexFile")
            throw ProgramResult(1)
        }
        val json: String
        if (indexFile.fileName.toString().endsWith(".tree")) {
            val tree = Struct.parseFrom(Files.readAllBytes(indexFile))
            json = JsonFormat.printer().print(tree)
        } else {
            val index = Index.parseFrom(Files.readAllBytes(indexFile))
            json =
                JsonFormat.printer().includingDefaultValueFields().print(index)
                    // protojson escapes <, >, & for HTML safety; unescape for human readability.
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">")
                    .replace("\\u0026", "&")
        }
        app.env.standardOutput.println(json)
    }
}

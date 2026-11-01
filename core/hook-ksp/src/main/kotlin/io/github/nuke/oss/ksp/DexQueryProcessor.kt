package io.github.nuke.oss.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate

// Names are matched as strings: the processor runs on the build machine and never links the module's classes.
private const val DEX_QUERIES = "io.github.nuke.oss.app.shared.dex.DexQueries"
private const val DEX_QUERY = "io.github.nuke.oss.app.shared.dex.DexQuery"
private const val HOSTS = "io.github.nuke.oss.app"
private const val SHARED = "shared"
private val QUERY_NAME = Regex("[A-Z][A-Z0-9_]*")

class DexQueryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        DexQueryProcessor(environment.codeGenerator, environment.logger)
}

// Lists every DexQuery property of the @DexQueries objects of a host, sorted by name, in that host package's
// generated generatedDexQueries. A query is named "<Object>.<PROPERTY>" after where it is declared, so the name in a
// log or cache key leads straight back to the source. A DexQuery held by any other object or at top level is an
// error: it would never be analysed, and the resolver would reject it at run time.
private class DexQueryProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val symbols = resolver.getSymbolsWithAnnotation(DEX_QUERIES).toList()
        val invalid = symbols.filterNot { it.validate() }
        if (invalid.isNotEmpty()) return invalid
        // A test compilation sees the main classes but declares no queries; an empty catalog there would shadow main.
        if (symbols.isEmpty()) return emptyList()
        val holders = symbols.mapNotNull(::holderOf)
        if (holders.size != symbols.size) return emptyList()
        val holderNames = holders.map { it.declaration.qualifiedName?.asString() }.toSet()
        resolver.getAllFiles().forEach { file -> checkStray(file.declarations, holderNames) }
        val queries = holders.flatMap(::queriesOf)
        for ((host, hostQueries) in queries.groupBy { it.host }) {
            val duplicates = hostQueries.groupBy { it.name }.filterValues { it.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                logger.error("Host $host declares Dex query names more than once: ${duplicates.joinToString()}")
                continue
            }
            write(host, hostQueries.sortedBy { it.name })
        }
        generated = true
        return emptyList()
    }

    private fun holderOf(symbol: KSAnnotated): Holder? {
        val declaration = symbol as? KSClassDeclaration
        if (declaration == null || declaration.classKind != ClassKind.OBJECT || declaration.isCompanionObject) {
            logger.error("@DexQueries applies to a named object", symbol)
            return null
        }
        val packageName = declaration.packageName.asString()
        val host = packageName.removePrefix("$HOSTS.").substringBefore('.')
        if (!packageName.startsWith("$HOSTS.") || host == SHARED) {
            logger.error("@DexQueries object must live in a host package $HOSTS.<host>", declaration)
            return null
        }
        if (generateSequence<KSDeclaration>(declaration) { it.parentDeclaration }.any { it.isPrivate() }) {
            logger.error("@DexQueries object ${declaration.simpleName.asString()} must not be private", declaration)
            return null
        }
        return Holder(host, declaration)
    }

    private fun queriesOf(holder: Holder): List<Query> {
        val declaration = holder.declaration
        // The object's path inside its package, which a nested object keeps.
        val objectName = declaration.qualifiedName!!.asString().removePrefix("${declaration.packageName.asString()}.")
        val declared = declaration.getDeclaredProperties().filter(::isQuery).toList()
        if (declared.isEmpty()) logger.error("@DexQueries object $objectName declares no Dex query", declaration)
        return declared.mapNotNull { property ->
            val name = property.simpleName.asString()
            when {
                property.isPrivate() -> null.also { logger.error("Dex query $objectName.$name must not be private", property) }
                !QUERY_NAME.matches(name) -> null.also { logger.error("Dex query $objectName.$name must be UPPER_SNAKE_CASE", property) }
                else -> Query(holder.host, "$objectName.$name", "${declaration.qualifiedName!!.asString()}.$name", declaration.containingFile)
            }
        }
    }

    // Objects and the top level are where a catalog member would be declared; a class property only refers to one.
    private fun checkStray(declarations: Sequence<KSDeclaration>, holders: Set<String?>) {
        for (declaration in declarations) {
            when (declaration) {
                is KSPropertyDeclaration -> if (isQuery(declaration)) {
                    logger.error("Dex query ${declaration.simpleName.asString()} is outside a @DexQueries object", declaration)
                }
                is KSClassDeclaration -> {
                    if (declaration.classKind == ClassKind.OBJECT && declaration.qualifiedName?.asString() !in holders) {
                        declaration.getDeclaredProperties().filter(::isQuery).forEach {
                            logger.error("Dex query ${it.simpleName.asString()} is outside a @DexQueries object", it)
                        }
                    }
                    checkStray(declaration.declarations.filterIsInstance<KSClassDeclaration>(), holders)
                }
                else -> Unit
            }
        }
    }

    private fun isQuery(property: KSPropertyDeclaration): Boolean =
        property.type.resolve().declaration.qualifiedName?.asString() == DEX_QUERY

    private fun write(host: String, queries: List<Query>) {
        val sources = queries.mapNotNull { it.file }.distinctBy(KSFile::filePath).toTypedArray()
        val packageName = "$HOSTS.$host"
        codeGenerator.createNewFile(Dependencies(aggregating = true, *sources), packageName, "GeneratedDexQueries")
            .bufferedWriter().use { out ->
                out.appendLine("// Generated by core/hook-ksp from every @DexQueries object in $packageName. Do not edit.")
                out.appendLine("package $packageName")
                out.appendLine()
                out.appendLine("internal val generatedDexQueries: Map<String, $DEX_QUERY> = mapOf(")
                for (query in queries) out.appendLine("    \"${query.name}\" to ${query.reference},")
                out.appendLine(")")
            }
        logger.info("Generated ${queries.size} Dex queries for host $host")
    }

    private class Holder(val host: String, val declaration: KSClassDeclaration)

    private data class Query(val host: String, val name: String, val reference: String, val file: KSFile?)
}

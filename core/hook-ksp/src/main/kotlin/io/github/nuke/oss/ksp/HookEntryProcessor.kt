package io.github.nuke.oss.ksp

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
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate

// Names are matched as strings: the processor runs on the build machine and never links the module's classes.
private const val HOOK_ENTRY = "io.github.nuke.oss.app.shared.registry.HookEntry"
private const val FEATURE_ENTRY = "io.github.nuke.oss.app.shared.registry.FeatureEntry"
private const val HOSTS = "io.github.nuke.oss.app"
private const val SHARED = "shared"

class HookEntryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        HookEntryProcessor(environment.codeGenerator, environment.logger)
}

// Lists every @HookEntry of a host, by order and then qualified name, in that host package's generated
// generatedHookEntries. An entry is an object that implements FeatureEntry, or a class whose companion object does.
// The host is the package segment after io.github.nuke.oss.app, so host packages still never reference each other.
private class HookEntryProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val symbols = resolver.getSymbolsWithAnnotation(HOOK_ENTRY).toList()
        val invalid = symbols.filterNot { it.validate() }
        if (invalid.isNotEmpty()) return invalid
        // A test compilation sees the main classes but declares no entries; an empty registry there would shadow main.
        if (symbols.isEmpty()) return emptyList()
        val entries = symbols.mapNotNull(::entryOf)
        if (entries.size != symbols.size) return emptyList()
        for ((host, hostEntries) in entries.groupBy { it.host }) {
            val services = hostEntries.map { it.services }.distinct()
            if (services.size != 1) {
                logger.error("Host $host mixes FeatureEntry service types: ${services.joinToString()}")
                continue
            }
            write(host, services.single(), hostEntries.sortedWith(compareBy<Entry> { it.order }.thenBy { it.name }))
        }
        generated = true
        return emptyList()
    }

    private fun entryOf(symbol: KSAnnotated): Entry? {
        val declaration = symbol as? KSClassDeclaration
            ?: return null.also { logger.error("@HookEntry applies to a class or an object", symbol) }
        val name = declaration.qualifiedName?.asString()
            ?: return null.also { logger.error("@HookEntry target needs a qualified name", declaration) }
        val packageName = declaration.packageName.asString()
        val host = packageName.removePrefix("$HOSTS.").substringBefore('.')
        if (!packageName.startsWith("$HOSTS.") || host == SHARED) {
            logger.error("@HookEntry target must live in a host package $HOSTS.<host>", declaration)
            return null
        }
        // The generated list reaches an object directly and a class through its companion, by the class name.
        val holder = when (declaration.classKind) {
            ClassKind.OBJECT -> declaration
            ClassKind.CLASS -> declaration.declarations.filterIsInstance<KSClassDeclaration>().firstOrNull { it.isCompanionObject }
                ?: return null.also { logger.error("@HookEntry class $name needs a companion object implementing FeatureEntry", declaration) }
            else -> return null.also { logger.error("@HookEntry applies to a class or an object", declaration) }
        }
        if (declaration.isPrivate() || holder.isPrivate()) {
            logger.error("@HookEntry target $name and its companion must not be private", declaration)
            return null
        }
        val services = servicesOf(holder)
            ?: return null.also { logger.error("$name must implement $FEATURE_ENTRY<services> directly", holder) }
        val order = declaration.annotations
            .first { it.annotationType.resolve().declaration.qualifiedName?.asString() == HOOK_ENTRY }
            .arguments.firstOrNull { it.name?.asString() == "order" }?.value as? Int ?: 0
        return Entry(host, "$HOSTS.$host", name, order, services, declaration.containingFile)
    }

    // The type argument of the FeatureEntry supertype, seen through type aliases.
    private fun servicesOf(holder: KSClassDeclaration): String? = holder.superTypes
        .map { expand(it.resolve()) }
        .firstOrNull { it.declaration.qualifiedName?.asString() == FEATURE_ENTRY }
        ?.arguments?.singleOrNull()?.type?.resolve()?.let(::expand)
        ?.declaration?.qualifiedName?.asString()

    private fun expand(type: KSType): KSType {
        val alias = type.declaration as? KSTypeAlias ?: return type
        val target = expand(alias.type.resolve())
        // An alias with its own parameters would need substitution; the registry's aliases have none.
        return if (alias.typeParameters.isEmpty()) target else type
    }

    private fun write(host: String, services: String, entries: List<Entry>) {
        val sources = entries.mapNotNull { it.file }.distinctBy(KSFile::filePath).toTypedArray()
        val packageName = entries.first().packageName
        codeGenerator.createNewFile(Dependencies(aggregating = true, *sources), packageName, "GeneratedHookEntries")
            .bufferedWriter().use { out ->
                out.appendLine("// Generated by core/hook-ksp from every @HookEntry in $packageName. Do not edit.")
                out.appendLine("package $packageName")
                out.appendLine()
                out.appendLine("internal val generatedHookEntries: List<$FEATURE_ENTRY<$services>> = listOf(")
                for (entry in entries) out.appendLine("    ${entry.name},")
                out.appendLine(")")
            }
        logger.info("Generated ${entries.size} hook entries for host $host")
    }

    private data class Entry(
        val host: String,
        val packageName: String,
        val name: String,
        val order: Int,
        val services: String,
        val file: KSFile?,
    )
}

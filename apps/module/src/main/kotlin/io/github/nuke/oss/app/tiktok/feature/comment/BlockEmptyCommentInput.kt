package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DESCRIPTOR_SEPARATOR
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method
import org.luckypray.dexkit.wrap.DexMethod

// Stops the comment keyboard from opening by itself when a video has no comments. The older panel asks with the comment
// count, which is zero; the newer one passes a keyboard request whose text names the source: the default scene, opened
// by the zero-comment board. The keyboard is skipped and a short notice says so. Opening the keyboard by hand still works.
@HookEntry
internal class BlockEmptyCommentInput(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val prompts: HostPrompts,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.EMPTY_INPUT)

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val tagged = resolver.resolve(listOf(CommentQueries.EMPTY_INPUT)).single().split(DESCRIPTOR_SEPARATOR)
        val steps = tagged.map { line ->
            val (panel, descriptor) = line.split(':', limit = 2)
            panel to DexMethod(descriptor).getMethodInstance(hostClassLoader)
        }
        // Skipping a step with a result is only safe for a step that returns nothing.
        for ((_, method) in steps) check(method.returnType == Void.TYPE) { "${method.declaringClass.name}.${method.name} returns ${method.returnType.name}" }
        val rules = steps.mapNotNull { (panel, method) -> rule(panel, method)?.let { method to it } }
        check(rules.isNotEmpty()) { "No keyboard step has a parameter to tell an automatic opening by" }
        rules.forEachIndexed { index, (method, applies) ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = "EmptyInput$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    if (applies { index -> call.argument(index) }) {
                        prompts.toast(R.string.host_notice_comment_input_blocked)
                        call.returnEarly(null)
                    }
                },
            ))
        }
    }

    // The test for one step, over its arguments by index; null when the step has nothing to tell the cases apart by.
    private fun rule(panel: String, method: Method): (((Int) -> Any?) -> Boolean)? = when (panel) {
        "old" -> method.parameterTypes.indexOfFirst { it == Long::class.javaPrimitiveType }.takeIf { it >= 0 }?.let { index ->
            { argument: (Int) -> Any? -> (argument(index) as? Long) == 0L }
        }
        else -> method.parameterTypes.indices.filter { !method.parameterTypes[it].isPrimitive }.takeIf { it.isNotEmpty() }?.let { indices ->
            { argument: (Int) -> Any? -> indices.any { index -> argument(index)?.toString()?.let(::isAutomaticRequest) == true } }
        }
    }

    companion object : TikTokFeature {
        const val ID = "BlockEmptyCommentInput"

        // The request text carries scene=DEFAULT, enterMethod=... and clickType=..., both set to the zero-comment board.
        internal fun isAutomaticRequest(text: String): Boolean {
            fun value(key: String) = Regex("""\b$key=([^,)\s]+)""").find(text)?.groupValues?.get(1)
            return value("scene") == "DEFAULT" && value("enterMethod") == BOARD && value("clickType") == BOARD
        }

        private const val BOARD = "zero_enter_comment_board"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(BlockEmptyCommentInput(resolver, hostClassLoader, prompts(ID)))
        }
    }
}

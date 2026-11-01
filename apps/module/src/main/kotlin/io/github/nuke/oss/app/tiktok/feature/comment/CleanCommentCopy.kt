package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatMessageFinder
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import io.github.nuke.oss.app.shared.dex.UsesDexQueries

// Copying someone else's comment puts "@author:" in front of the text. The copy action hands its text out through its
// own String getter (its other String getter, the action tag, overrides one of the long-press item's, whether that
// comes from a superclass or an interface). The copied text becomes the comment's own text when the action's comment can be found; otherwise the prefix is removed
// from what the getter returned.
@HookEntry
internal class CleanCommentCopy(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val commentName: String = COMMENT,
) : RuntimeFeature, UsesDexQueries {
    override val id = "CleanCommentCopy"
    override val dexQueries = listOf(FeedQueries.COMMENT_COPY)
    private val text = NamedField("text")

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, FeedQueries.COMMENT_COPY).single()
        val action = anchor.declaringClass
        val getters = action.declaredMethods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java && !it.isSynthetic && !it.isBridge && !overridesSuper(it)
        }
        check(getters.size == 1) { "Expected one copied-text getter in ${action.name}, found ${getters.size}" }
        val copied = getters.single().apply { isAccessible = true }
        val finder = ChatMessageFinder(commentName.toClass(hostClassLoader))
        scope.hooks.install(copied, HookSpec(
            id = "StripAuthor",
            requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
            after = { call ->
                val returned = (call.outcome as? CallOutcome.Returned)?.value as? String ?: return@HookSpec
                val comment = finder.find(call.receiver)
                val own = comment?.let { text.of(it)?.get(it) as? String }?.takeIf { it.isNotBlank() }
                call.returnValue(own ?: stripAuthorPrefix(returned))
            },
        ))
    }

    // Douyin 40.7.0 declares the action tag on an interface the long-press item implements, not on a superclass.
    private fun overridesSuper(method: java.lang.reflect.Method): Boolean =
        supertypes(method.declaringClass).any { type -> type.declaredMethods.any { it.name == method.name && it.parameterCount == 0 } }

    private fun supertypes(type: Class<*>): Sequence<Class<*>> = sequence {
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        type.superclass?.let(queue::add)
        queue += type.interfaces
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == Any::class.java || !seen.add(next)) continue
            yield(next)
            next.superclass?.let(queue::add)
            queue += next.interfaces
        }
    }

    companion object : TikTokFeature {
        const val COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment"
        // Anchored: the mention leads the copied text, and a later "@name:" is part of the comment itself.
        private val AUTHOR_PREFIX = Regex("^@[^:]+:\\s*")

        internal fun stripAuthorPrefix(text: String): String = AUTHOR_PREFIX.replaceFirst(text, "")

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CleanCommentCopy(resolver, hostClassLoader))
        }
    }
}

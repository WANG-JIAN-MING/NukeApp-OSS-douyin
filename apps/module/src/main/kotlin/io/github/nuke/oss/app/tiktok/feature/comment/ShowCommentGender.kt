package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.util.concurrent.atomic.AtomicBoolean

// Appends the commenter's gender to the location label shown beside a comment (the comment's ip_label), as ♂ or ♀.
// The label is rewritten before a step that shows it runs, and every step that takes a Comment and reads the label is
// hooked, because the newer cell, the older IP component and the comment header read it separately. A label that
// already carries the mark is left alone, because the host binds the same comment more than once.
@HookEntry
internal class ShowCommentGender(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val commentName: String = COMMENT,
    private val logger: Logger = Logger {},
) : RuntimeFeature, UsesDexQueries {
    override val id = "ShowCommentGender"
    override val dexQueries = listOf(CommentQueries.COMMENT_IP_READERS)
    // Douyin 40.7.0 names these fields in camelCase (ipLabel, commentUser); the snake_case names are what the host
    // serializes them under. commentUser is the commenter when the host sets it with a known gender; otherwise the
    // gender of user is used, which is what the older host versions carried.
    private val commentUser = NamedFieldAlias("commentUser", "comment_user")
    private val user = NamedField("user")
    private val gender = NamedField("gender")
    private val label = NamedFieldAlias("ipLabel", "ip_label")
    private val fired = AtomicBoolean()
    private val unmarked = AtomicBoolean()
    private val marked = AtomicBoolean()

    override fun install(scope: FeatureScope) {
        val readers = resolver.methodLists(hostClassLoader, CommentQueries.COMMENT_IP_READERS).single()
        val hooks = readers.mapNotNull { method -> method.parameterTypes.indexOfFirst { it.name == commentName }.takeIf { it >= 0 }?.let { method to it } }
        check(hooks.isNotEmpty()) { "No step that shows the location label takes $commentName" }
        logger.log("feature=$id installed steps=${hooks.size}")
        hooks.forEachIndexed { index, (method, argument) ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "CommentGender$index", before = { call -> call.argument(argument)?.let(::mark) }))
        }
    }

    private fun mark(comment: Any) {
        if (fired.compareAndSet(false, true)) logger.log("feature=$id step_fired")
        val field = label.of(comment)?.takeIf { it.type == String::class.java }
        if (field == null) return skip("no_label_field")
        val genders = listOf(
            commentUser.of(comment)?.get(comment)?.let(::genderOf),
            user.of(comment)?.get(comment)?.let(::genderOf),
        )
        val suffix = when (genders.firstOrNull { it == MALE || it == FEMALE }) {
            MALE -> "(♂)"
            FEMALE -> "(♀)"
            else -> return skip("gender_unknown commentUser=${genders[0]} user=${genders[1]}")
        }
        val current = field.get(comment) as? String ?: return skip("label_null")
        if (current.endsWith(suffix)) return
        field.set(comment, current + suffix)
        if (marked.compareAndSet(false, true)) logger.log("feature=$id marked")
    }

    private fun genderOf(author: Any): Int? = gender.of(author)?.takeIf { it.type == Int::class.javaPrimitiveType }?.getInt(author)

    private fun skip(reason: String) {
        if (unmarked.compareAndSet(false, true)) logger.log("feature=$id skipped $reason")
    }

    companion object : TikTokFeature {
        const val COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment"
        private const val MALE = 1
        private const val FEMALE = 2

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ShowCommentGender(resolver, hostClassLoader, logger = logger))
        }
    }
}

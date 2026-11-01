package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Whether the button group under a comment row, found by its content description, is the like or the dislike one.
// The host marks the dislike as a FrameLayout described with "踩" and the like as a LinearLayout described with "赞";
// the group is the view's parent, unless that is the list itself.
internal fun commentButtonToHide(description: String?, frame: Boolean, linear: Boolean, parentName: String, like: Boolean, dislike: Boolean): Boolean {
    if (description == null || parentName == "CommentRecyclerView") return false
    return (dislike && frame && "踩" in description) || (like && linear && "赞" in description)
}

// Hides the buttons of one older-panel comment row.
internal interface CommentButtons {
    fun hide(row: Any, like: Boolean, dislike: Boolean)
}

// Hides the like and the dislike buttons under comments. The newer panel builds them from a state class whose nine
// constructor values include one flag each for showing them, so after the state is built those two flags are cleared
// in the object (the constructor cannot be rewritten before it runs). The older panel's rows are hidden by looking
// for the buttons in each row after it is bound. Whichever panel the host has is handled.
@HookEntry
internal class HideCommentButtons(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val buttons: () -> CommentButtons,
    private val logger: Logger,
    options: OptionValues,
    private val holderName: String = HOLDER,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = if (options.toggle(LIKE) || options.toggle(DISLIKE)) listOf(CommentQueries.BUTTONS_STATE, CommentQueries.COMMENT_ADAPTER) else emptyList()
    // Either panel is enough; the feature only fails when the host has neither.
    override fun unusable(missing: List<DexQuery>) = missing.size == dexQueries.size
    private val like = options.toggle(LIKE)
    private val dislike = options.toggle(DISLIKE)
    private val itemView = NamedField("itemView")

    override fun install(scope: FeatureScope) {
        if (!like && !dislike) return
        val installed = listOf(installState(scope), installAdapter(scope)).count { it }
        check(installed > 0) { "Neither comment panel has a place to hide the buttons" }
    }

    // A panel the host does not have is not an error as long as the other one is there.
    private fun located(query: DexQuery) = try {
        resolver.methods(hostClassLoader, query).single()
    } catch (failure: IllegalStateException) {
        null.also { logger.log("feature=$ID panel_skipped ${resolver.nameOf(query)}") }
    }

    // The state class is the one the located method is declared in; its constructor must have this shape
    // (four flags, a number, four flags), and its fields the same, so the right flags are the ones cleared.
    private fun installState(scope: FeatureScope): Boolean {
        val type = located(CommentQueries.BUTTONS_STATE)?.declaringClass ?: return false
        val bool = Boolean::class.javaPrimitiveType
        val shape = listOf(bool, bool, bool, bool, Int::class.javaPrimitiveType, bool, bool, bool, bool)
        val members = type.resolve().optional(silent = true)
        val constructor = members.firstConstructorOrNull { parameters { it == shape } }?.self ?: return false
        val fields = members.field { modifiersNot(Modifiers.STATIC) }.map { it.self }
        if (fields.map { it.type } != shape) return false.also { logger.log("feature=$ID state_skipped fields do not match the constructor") }
        fields.forEach { it.isAccessible = true }
        constructor.isAccessible = true
        scope.hooks.install(constructor, HookSpec(
            id = "ButtonsState",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call ->
                val state = call.receiver
                if (call.outcome is CallOutcome.Returned && state != null) {
                    if (dislike) fields[DISLIKE_FLAG].setBoolean(state, false)
                    if (like) fields[LIKE_FLAG].setBoolean(state, false)
                }
            },
        ))
        return true
    }

    private fun installAdapter(scope: FeatureScope): Boolean {
        val adapter = located(CommentQueries.COMMENT_ADAPTER)?.declaringClass ?: return false
        val steps = adapter.resolve().optional(silent = true).method {
            parameters { it.size == 1 && it[0].name == holderName }
            isSynthetic = false
        }.map { it.self }
        if (steps.isEmpty()) return false
        val ui = buttons()
        steps.forEachIndexed { index, step ->
            step.isAccessible = true
            scope.hooks.install(step, HookSpec(id = "CommentRow$index", after = { call ->
                val row = call.argument(0)?.let { holder -> itemView.of(holder)?.get(holder) }
                if (call.outcome is CallOutcome.Returned && row != null) ui.hide(row, like, dislike)
            }))
        }
        return true
    }

    companion object : TikTokFeature {
        const val ID = "HideCommentButtons"
        const val LIKE = "like"
        const val DISLIKE = "dislike"
        const val HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"
        private const val LIKE_FLAG = 5
        private const val DISLIKE_FLAG = 7
        val OPTIONS = listOf(ToggleOption(LIKE), ToggleOption(DISLIKE))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideCommentButtons(resolver, hostClassLoader, commentButtons, logger, options(ID, OPTIONS)))
        }
    }
}

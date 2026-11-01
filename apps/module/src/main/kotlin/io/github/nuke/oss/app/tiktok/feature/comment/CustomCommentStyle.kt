package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinAccountReader
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Field
import java.lang.reflect.Modifier

// Changes how a comment is marked up when it is published, by rewriting the text extras of the publish request: the
// ranges that the host draws as hashtags, mentions or locations. Other people see the markup, so this is a change to
// what the comment looks like to everyone, not only on this device. The host's server may drop markup it does not
// accept, which is why there are two blue styles:
//   hashtag  - one hashtag extra over the whole text, drawn blue; some viewers see it as plain text.
//   mention  - one mention extra over the whole text pointing at the user's own ID, with an "@" added at the end because
//              the host refuses a mention-only comment without it.
//   location - "[位置]" in front of the text and a location extra over all of it.
@HookEntry
internal class CustomCommentStyle(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    options: OptionValues,
    private val currentUserId: () -> String?,
    private val extraName: String = TEXT_EXTRA,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(CommentQueries.COMMENT_SEND)
    private val style = options.choice(STYLE)

    override fun install(scope: FeatureScope) {
        val send = resolver.methods(hostClassLoader, CommentQueries.COMMENT_SEND).single()
        check(send.parameterCount >= 1) { "${send.declaringClass.name}.${send.name} takes no publish request" }
        val request = send.parameterTypes[0]
        val chain = generateSequence(request) { it.superclass }.takeWhile { it != Any::class.java }.toList()
        val fields = chain.flatMap { it.declaredFields.asList() }.filter { !Modifier.isStatic(it.modifiers) }
        // Current hosts keep other String fields (send method, emoji order) and a second, assigned-later list next to
        // the two that matter, so a tie is broken by what the request declares: the text is the one field typed exactly
        // CharSequence, and the extras are the list the request is constructed with, which is final.
        val texts = fields.filter { CharSequence::class.java.isAssignableFrom(it.type) }
        val textField = (texts.singleOrNull() ?: texts.filter { it.type == CharSequence::class.java }.also { check(it.size == 1) { "Expected one text field in ${request.name}, found ${texts.size}" } }.single())
        val lists = fields.filter { List::class.java.isAssignableFrom(it.type) }
        val extrasField = (lists.singleOrNull() ?: lists.filter { Modifier.isFinal(it.modifiers) }.also { check(it.size == 1) { "Expected one list field in ${request.name}, found ${lists.size}" } }.single())
        val extra = extraName.toClass(hostClassLoader)
        val members = listOf("type", "start", "end", "userId").associateWith { name ->
            extra.resolve().optional(silent = true).firstFieldOrNull { this.name = name; superclass() }?.self
                ?: error("$extraName has no $name")
        }
        check(style in STYLES) { "$style is not a comment style" }
        listOf(textField, extrasField).plus(members.values).forEach { it.isAccessible = true }
        send.isAccessible = true
        scope.hooks.install(send, HookSpec(id = "CommentStyle", before = { call ->
            call.argument(0)?.let { published ->
                try {
                    // The account is read when a comment is published, not at install: the host starts this process
                    // before its account service answers. A mention of nobody is never published, so without an ID
                    // the comment goes out unchanged.
                    val userId = if (style == MENTION) currentUserId() else null
                    if (style == MENTION && userId == null) {
                        logger.log("feature=$ID user_unavailable")
                    } else {
                        restyle(published, textField, extrasField, extra, members, userId)
                    }
                } catch (failure: ReflectiveOperationException) {
                    logger.log("feature=$ID restyle_failed ${failure.javaClass.simpleName}")
                }
            }
        }))
    }

    private fun restyle(published: Any, textField: Field, extrasField: Field, extra: Class<*>, members: Map<String, Field>, userId: String?) {
        var text = textField.get(published) as? CharSequence ?: return
        if (text.isEmpty()) return
        text = when (style) {
            LOCATION -> "[位置]$text"
            MENTION -> "$text@"
            else -> text
        }
        if (style != HASHTAG) textField.set(published, text)
        @Suppress("UNCHECKED_CAST")
        val extras = extrasField.get(published) as? MutableList<Any?> ?: return
        if (extras.isEmpty()) extras += extra.resolve().firstConstructor { emptyParameters() }.create()
        for (item in extras) {
            if (item == null) continue
            when (style) {
                HASHTAG -> members.getValue("type").setInt(item, TYPE_HASHTAG)
                MENTION -> {
                    members.getValue("type").setInt(item, TYPE_MENTION)
                    members.getValue("userId").set(item, userId)
                }
                else -> members.getValue("type").setInt(item, TYPE_LOCATION)
            }
            members.getValue("start").setInt(item, 0)
            members.getValue("end").setInt(item, text.length)
        }
    }

    companion object : TikTokFeature {
        const val ID = "CustomCommentStyle"
        const val STYLE = "style"
        const val HASHTAG = "hashtag"
        const val MENTION = "mention"
        const val LOCATION = "location"
        const val TEXT_EXTRA = "com.ss.android.ugc.aweme.model.TextExtraStruct"
        private const val TYPE_HASHTAG = 1
        private const val TYPE_MENTION = 0
        private const val TYPE_LOCATION = 33
        private val STYLES = listOf(HASHTAG, MENTION, LOCATION)
        val OPTIONS = listOf(ChoiceOption(STYLE, STYLES, default = HASHTAG))

        // The signed-in account's ID from the host's account service, or null while nobody is signed in, the service is
        // not up yet or the call is not there.
        fun accountUserId(hostClassLoader: ClassLoader, logger: Logger = Logger {}): () -> String? {
            val reader = DouyinAccountReader(DouyinAccountReader.service(hostClassLoader)) { logger.log("feature=$ID account_failed ${it.javaClass.simpleName}") }
            return { (reader.read() as? HostAccount.SignedIn)?.userId?.takeIf { it.isNotBlank() } }
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomCommentStyle(resolver, hostClassLoader, logger, options(ID, OPTIONS), accountUserId(hostClassLoader, logger)))
        }
    }
}

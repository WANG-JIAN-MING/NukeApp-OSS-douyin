package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.media.MediaFiles
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference

// What the header row can copy about an author. Everything comes from the User the page already holds.
internal class AuthorInfo(val uid: String, val name: String, val signature: String, val avatar: String)

// The author's details as text, one field per line, for the clipboard.
internal fun authorSummary(info: AuthorInfo): String =
    "UID: ${info.uid}\nName: ${info.name}\nSignature: ${info.signature}\nAvatar: ${info.avatar}"

// The full-information buttons: json prints every field of the author's User, or null when the host could not print
// it; save writes such a text to a file off the main thread and reports where it went.
internal class AuthorFullInfo(val json: () -> String?, val save: (context: Any, json: String) -> Unit)

// Adds a row of copy buttons at the top of an author's profile. attach is called with the page's root View and the
// author each time the header is set up; the row appears once the header has grown, so it retries briefly. full is
// null unless the full-information option is on.
internal interface AuthorRow {
    fun attach(root: Any, info: AuthorInfo, full: AuthorFullInfo?)
}

// Prints an object with the host's own Gson, pretty printed.
internal fun hostGson(loader: ClassLoader): (Any) -> String {
    val builder = GSON_BUILDER.toClass(loader)
    val pretty = builder.getMethod("setPrettyPrinting")
    val create = builder.getMethod("create")
    val toJson = create.returnType.getMethod("toJson", Any::class.java)
    // Gson instances are thread-safe, so one serves every page. It is built on first use: the feature installs while the
    // host's class loader is still being created, and building a Gson there runs the host's adapters and fails. A failed
    // build is not kept, so the next use tries again.
    val gson by lazy { create.invoke(pretty.invoke(builder.getConstructor().newInstance())) }
    return { value -> toJson.invoke(gson, value) as String }
}

private const val GSON_BUILDER = "com.google.gson.GsonBuilder"

// Adds copy buttons for the author's UID and details to the top of the profile page. The header is the class that binds
// the page's user in void methods taking a User; the author is that argument. The row goes into the container of the
// header's info area when the header has one, else into the first View the header holds. With "full" on, two more
// buttons copy or save every field of that User as JSON.
@HookEntry
internal class AddAuthorInfoRow(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val row: () -> AuthorRow,
    options: OptionValues,
    private val prompts: HostPrompts,
    private val files: () -> MediaFiles,
    private val logger: Logger,
    private val isRoot: (Any?) -> Boolean = ::isView,
    private val parentOf: (Any) -> Any? = ::parentView,
    private val userName: String = USER,
    private val printer: (ClassLoader) -> (Any) -> String = ::hostGson,
    private val runner: BackgroundRunner = threadRunner,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.AUTHOR_HOME)
    private val full = options.toggle(FULL)
    private val uid = NamedField("uid")
    private val nickname = NamedField("nickname")
    private val signature = NamedField("signature")
    // The host's model names these avatarThumb and urlList; avatar_thumb and url_list are the serialized names.
    private val avatar = NamedFieldAlias("avatarThumb", "avatar_thumb")
    private val urls = NamedFieldAlias("urlList", "url_list")

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, FeedQueries.AUTHOR_HOME).single()
        val userType = userName.toClass(hostClassLoader)
        val headers = anchor.declaringClass.resolve().optional(silent = true).method {
            isSynthetic = false
            returnType = Void.TYPE
            parameters(userType)
        }.map { it.self }
        check(headers.isNotEmpty()) { "${anchor.declaringClass.name} has no method that takes a ${userType.simpleName}" }
        headers.forEach { it.isAccessible = true }
        // Resolved here, so a host without Gson fails the install instead of a button doing nothing.
        val print = if (full) printer(hostClassLoader) else null
        if (print != null) prompts.install(scope)
        val media = if (print != null) files() else null
        val ui = row()
        headers.forEachIndexed { index, header ->
            scope.hooks.install(header, HookSpec(id = "AuthorHeader$index", after = { call ->
                val page = call.receiver
                val author = call.argument(0)?.takeIf(userType::isInstance)
                val root = page?.let { infoContainer(it) ?: firstView(it) }
                val uidText = author?.let { text(it, uid) }
                if (call.outcome is CallOutcome.Returned && author != null && root != null && uidText != null) {
                    val details = if (print != null && media != null) fullInfo(author, uidText, print, media) else null
                    ui.attach(root, AuthorInfo(uidText, text(author, nickname).orEmpty(), text(author, signature).orEmpty(), avatarUrl(author)), details)
                }
            }))
        }
    }

    // The author is held weakly: the row outlives neither the page nor the User it shows.
    private fun fullInfo(author: Any, uid: String, print: (Any) -> String, media: MediaFiles): AuthorFullInfo {
        val held = WeakReference(author)
        return AuthorFullInfo(
            json = {
                try {
                    held.get()?.let(print)
                } catch (failure: ReflectiveOperationException) {
                    logger.log("feature=$ID print_failed ${(failure.cause ?: failure).javaClass.simpleName}")
                    null
                }
            },
            save = { context, json ->
                runner.run(0) {
                    try {
                        prompts.toast(R.string.host_author_saved, media.saveText(context, "author_${uid.filter { it.isLetterOrDigit() }}_${System.currentTimeMillis()}.json", json))
                    } catch (failure: java.io.IOException) {
                        prompts.toast(R.string.host_author_save_failed, failure.javaClass.simpleName)
                    } catch (failure: IllegalStateException) {
                        prompts.toast(R.string.host_author_save_failed, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            },
        )
    }

    // The header keeps an info area with two Views: a column and the container the column was added to. The container
    // also holds the rest of the header's text, so the row goes into it. The area is found by its class name, which the
    // host does not obfuscate, and the container as the View that is the parent of another View the area holds.
    private fun infoContainer(page: Any): Any? {
        val area = fields(page.javaClass).firstNotNullOfOrNull { field ->
            field.takeIf { it.type.simpleName == INFO_AREA }?.get(page)
        } ?: return null
        val views = fields(area.javaClass).mapNotNull { it.get(area)?.takeIf(isRoot) }
        return views.firstOrNull { container -> views.any { it !== container && parentOf(it) === container } }
    }

    private fun fields(type: Class<*>) = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
        .onEach { it.isAccessible = true }
        .toList()

    // The hosts differ in whether the header receives its root View or keeps it in a field.
    private fun firstView(page: Any): Any? = generateSequence(page.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
        .mapNotNull { field -> field.apply { isAccessible = true }.get(page)?.takeIf(isRoot) }
        .firstOrNull()

    private fun text(owner: Any, field: NamedField): String? = field.of(owner)?.get(owner) as? String

    private fun avatarUrl(author: Any): String {
        val thumb = avatar.of(author)?.get(author) ?: return ""
        return (urls.of(thumb)?.get(thumb) as? List<*>)?.firstOrNull() as? String ?: ""
    }

    companion object : TikTokFeature {
        const val ID = "AddAuthorInfoRow"
        const val FULL = "full"
        const val USER = "com.ss.android.ugc.aweme.profile.model.User"
        private const val INFO_AREA = "PandaHeaderInfoArea"
        val OPTIONS = listOf(ToggleOption(FULL))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AddAuthorInfoRow(resolver, hostClassLoader, authorRow, options(ID, OPTIONS), prompts(ID), mediaFiles, logger))
        }
    }
}

internal fun parentView(view: Any): Any? = (view as? android.view.View)?.parent

internal fun isView(value: Any?): Boolean = generateSequence(value?.javaClass) { it.superclass }.any { it.name == "android.view.View" }

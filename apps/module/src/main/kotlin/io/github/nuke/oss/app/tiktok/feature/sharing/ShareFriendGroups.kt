package io.github.nuke.oss.app.tiktok.feature.sharing

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.data.downloadImage
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.constructors
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.callByName
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.feature.chat.BackgroundRunner
import io.github.nuke.oss.app.tiktok.feature.chat.threadRunner
import io.github.nuke.oss.app.tiktok.query.ShareGroupQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ImAccount
import io.github.nuke.oss.app.tiktok.sdk.contact.DouyinContacts
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Share a post to a configured group of friends from the share panel. When the panel's friend picker is built, the
// groups are painted before the first row of its friend list, each with its custom picture or its first friend's avatar; tapping one shares the panel's post to every friend of
// the group in one call to the host's IM share service, the way the host shares to several friends it picked itself.
// A group is sent only when every one of its friends is still in the friend list, and only while the same picker
// still shows the same post for the same account. One share runs at a time. The host's callbacks are summed up into
// succeeded, failed and unknown; they say the host handled the share, not that a friend received it.
@HookEntry
internal class ShareFriendGroups(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val friends: () -> List<Pair<String, Any>>,
    private val imService: () -> Any?,
    private val account: () -> Long,
    private val prefixes: FriendPrefixes,
    private val prompts: HostPrompts,
    private val main: TaskScheduler,
    private val logger: Logger,
    // Where the settings screen saves the groups' custom pictures.
    private val avatarDirectory: File,
    private val runner: BackgroundRunner = threadRunner,
    private val download: (url: String) -> ByteArray? = ::downloadImage,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(ShareGroupQueries.FRIEND_PICKER, ShareGroupQueries.SHARE_WITH_CALLBACK)
    private val lines = options.lines(GROUPS)
    private val avatarLines = options.lines(AVATARS)
    private val active = AtomicBoolean()
    private val sending = AtomicBoolean()

    override fun install(scope: FeatureScope) {
        val groups = parseShareGroups(lines)
        val avatars = parseShareAvatars(avatarLines, groups)
        val picker = resolver.constructors(hostClassLoader, ShareGroupQueries.FRIEND_PICKER).single()
        val send = resolver.methods(hostClassLoader, ShareGroupQueries.SHARE_WITH_CALLBACK).single()
        picker.isAccessible = true
        send.isAccessible = true
        val argumentsType = picker.parameterTypes.single()
        val packageField = single(argumentsType, SHARE_PACKAGE.toClass(hostClassLoader))
        val listField = single(picker.declaringClass, RECYCLER.toClass(hostClassLoader))
        val share = HostGroupShare(hostClassLoader, send, imService, main)
        prompts.install(scope)
        prefixes.install(scope, ID)
        active.set(true)
        scope.own(AutoCloseable { active.set(false) })
        if (groups.isEmpty()) return
        val shown = groups.map { PrefixGroup(it.name, it.uids.size, avatarOf(it, avatars[it.name])) }
        scope.hooks.install(picker, HookSpec(
            id = "Picker",
            requiredCapabilities = setOf(HookCapability.CONSTRUCTOR_OBSERVE_AFTER),
            after = { call ->
                if (!active.get() || call.outcome !is CallOutcome.Returned) return@HookSpec
                val arguments = call.argument(0) ?: return@HookSpec
                val sharePackage = packageField.get(arguments) ?: return@HookSpec
                val list = call.receiver?.let(listField::get) ?: return@HookSpec
                val page = Page(WeakReference(arguments), WeakReference(sharePackage), WeakReference(list), packageField)
                prefixes.attach(list, shown) { index -> tapped(groups[index], page, share) }
            },
        ))
    }

    // The picker a tap came from. current answers the post only while that picker is on screen and still shows it.
    private inner class Page(
        private val arguments: WeakReference<Any>,
        private val sharePackage: WeakReference<Any>,
        private val list: WeakReference<Any>,
        private val packageField: Field,
    ) {
        fun current(): Any? {
            val expected = sharePackage.get() ?: return null
            val shown = arguments.get()?.let(packageField::get)
            val view = list.get() ?: return null
            return expected.takeIf { shown === it && prefixes.isAttached(view) }
        }
    }

    private fun tapped(group: ShareGroup, page: Page, share: HostGroupShare) {
        if (!active.get()) return
        if (!sending.compareAndSet(false, true)) return prompts.toast(R.string.host_notice_share_groups_busy)
        runner.run(0) {
            try {
                send(group, page, share)
            } finally {
                sending.set(false)
            }
        }
    }

    private fun send(group: ShareGroup, page: Page, share: HostGroupShare) {
        val result = try {
            val owner = account()
            check(owner.toString() !in group.uids) { "${group.name} lists your own account" }
            val recipients = resolveShareGroup(group, friends())
            val sharePackage = checkNotNull(page.current()) { "The share page was closed or changed" }
            check(active.get()) { "The feature was turned off" }
            check(account() == owner) { "Account changed" }
            prompts.toast(R.string.host_notice_share_groups_working, group.name, recipients.size)
            share.share(sharePackage, recipients, group.uids.toSet(), TIMEOUT_MILLIS)
        } catch (failure: IllegalStateException) {
            return failed(failure)
        } catch (failure: HostContactsUnavailable) {
            return failed(failure)
        }
        if (!result.dispatched) {
            logger.log("feature=$ID not_dispatched")
            return prompts.toast(R.string.host_notice_share_groups_failed, (result.error ?: "?").take(REASON_LENGTH))
        }
        // The counts only; never which friends or which post.
        logger.log("feature=$ID shared accepted=${result.accepted} failed=${result.failed} unknown=${result.unknown} stopped=${result.error != null}")
        prompts.toast(R.string.host_notice_share_groups_result, result.accepted, result.failed, result.unknown)
    }

    private fun failed(failure: Exception) {
        logger.log("feature=$ID failed ${failure.javaClass.simpleName}")
        prompts.toast(R.string.host_notice_share_groups_failed, (failure.message ?: failure.javaClass.simpleName).take(REASON_LENGTH))
    }

    // The custom picture when there is one, otherwise the first friend's avatar from the host's friend list.
    private fun avatarOf(group: ShareGroup, file: String?): PrefixAvatar {
        if (file != null) return PrefixAvatar("file:$file") {
            File(avatarDirectory, file).takeIf { it.isFile && it.length() <= MAX_AVATAR_BYTES }?.readBytes()
        }
        val first = group.uids.first()
        return PrefixAvatar("uid:$first") {
            val user = friends().firstOrNull { it.first == first }?.second
            user?.let(DouyinContacts::avatarUrlOf)?.let(download)
        }
    }

    private fun single(owner: Class<*>, type: Class<*>): Field {
        val found = owner.resolve().optional(silent = true).field { this.type = type }
        check(found.size == 1) { "Expected one ${type.name} field in ${owner.name}, found ${found.size}" }
        return found.single().self.apply { isAccessible = true }
    }

    companion object : TikTokFeature {
        const val ID = "ShareFriendGroups"
        const val GROUPS = "groups"
        const val AVATARS = "avatars"
        private const val MAX_AVATAR_BYTES = 2L * 1024 * 1024
        private const val SHARE_PACKAGE = "com.ss.android.ugc.aweme.sharer.ui.SharePackage"
        private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
        private const val TIMEOUT_MILLIS = 30_000L
        private const val REASON_LENGTH = 200
        val OPTIONS = listOf(LinesOption(GROUPS), LinesOption(AVATARS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ShareFriendGroups(
                resolver, hostClassLoader, options(ID, OPTIONS), DouyinContacts.friendObjects(resolver, hostClassLoader),
                DouyinContacts.imService(hostClassLoader), ImAccount(hostClassLoader)::id, friendPrefixes(), prompts(ID), scheduler, logger,
                File(featureFiles, ID),
            ))
        }
    }
}

// One call to IImShareService.shareWithCallBack with a callback that sums up what the host reports. The call is made on
// the main thread, as the host's own share sheet makes it; the calling worker thread waits for the end of the share
// or the timeout. Callbacks may arrive on any thread and after the timeout, when they are ignored.
internal class HostGroupShare(
    private val hostClassLoader: ClassLoader,
    private val send: Method,
    private val imService: () -> Any?,
    private val main: TaskScheduler,
) {
    private val callbackType = send.parameterTypes[CALLBACK]

    fun share(sharePackage: Any, recipients: List<Any>, uids: Set<String>, timeoutMillis: Long): GroupShareResult {
        val outcome = ShareGroupOutcome(uids)
        val ended = ArrayBlockingQueue<String>(1)
        var open = true
        var dispatched = false
        val callback = Proxy.newProxyInstance(hostClassLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "NukeShareGroupCallback"
            }
            synchronized(outcome) {
                if (open) {
                    when (method.name) {
                        "onShareSuccessWithSingleUser" -> outcome.record(listOfNotNull(uidOf(args?.getOrNull(1))), true)
                        "onShareSuccessWithUser", "onShareFailedWithUser" ->
                            outcome.record((args?.getOrNull(1) as? List<*>).orEmpty().mapNotNull(::uidOf), method.name == "onShareSuccessWithUser")
                        "onShareFailed" -> outcome.record(uids, false)
                    }
                    if (method.name in ENDS || method.name in NAMING && outcome.unknownCount == 0) ended.offer("")
                }
            }
            // onPreShare answering false lets the host share as it would; nothing else is answered for the host.
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        }
        main.schedule(0) {
            try {
                val im = imService() ?: error("The IM service is unavailable")
                val service = im.callByName("getShareService") ?: error("The share service is unavailable")
                check(send.declaringClass.isInstance(service)) { "The share service is not the located one" }
                // A worker that already gave up has told the user nothing was sent, so a late turn must not send.
                synchronized(outcome) {
                    if (!open) return@schedule
                    dispatched = true
                }
                send.invoke(service, sharePackage, recipients, callback, null)
            } catch (failure: InvocationTargetException) {
                ended.offer(failure.targetException.toString())
            } catch (failure: Exception) {
                ended.offer(failure.toString())
            }
        }
        val end = ended.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: "No answer from the host in ${timeoutMillis / 1000} s"
        return synchronized(outcome) {
            open = false
            GroupShareResult(dispatched, outcome.accepted.size, outcome.failed.size, outcome.unknownCount, end.takeIf { it.isNotEmpty() })
        }
    }

    private fun uidOf(contact: Any?): String? = try {
        contact?.callByName("getUid") as? String
    } catch (_: ReflectiveOperationException) {
        null
    }

    private companion object {
        const val CALLBACK = 2
        val ENDS = setOf("onShareFailed", "onShareCancel", "onShareSessionEnd")
        val NAMING = setOf("onShareSuccessWithSingleUser", "onShareSuccessWithUser", "onShareFailedWithUser")
    }
}

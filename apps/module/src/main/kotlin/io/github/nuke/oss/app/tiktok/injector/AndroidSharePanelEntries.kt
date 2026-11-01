package io.github.nuke.oss.app.tiktok.injector

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.android.dx.stock.ProxyBuilder
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntries
import io.github.nuke.oss.app.tiktok.feature.sharing.SharePanelEntry
import io.github.nuke.oss.app.tiktok.sdk.ModuleStrings
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

// The host's share rows are instances of an abstract function class with four abstract getters (a label, a key, an icon
// and one more) and an abstract click. A row of ours is a generated subclass of it whose getters answer from the entry,
// and whose click calls the entry, after checking that the panel still shows the post it was made for. The subclass is
// generated once per feature when it installs, into a code cache under the host's data directory, and extends the host
// class while loading module classes through a loader that sees both. None of it has been run on a real host.
internal class AndroidSharePanelEntries(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val cacheDirectory: File,
    private val strings: ModuleStrings,
    private val logger: Logger,
) : SharePanelEntries {
    override fun add(scope: FeatureScope, featureId: String, entry: SharePanelEntry) {
        val (bind, initialize, label, clickName, factory) = resolver.methods(hostClassLoader, SharePanelEntries.QUERIES)
        listOf(bind, initialize, label, clickName, factory).forEach { it.isAccessible = true }
        val functionClass = initialize.declaringClass
        val argsClass = initialize.parameterTypes.single()
        val packageClass = SHARE_PACKAGE.toClass(hostClassLoader)
        val panelArgs = field(bind.declaringClass, argsClass)
        val argsPackage = field(argsClass, packageClass)
        val shareUtil = checkNotNull(factory.invoke(null)) { "The share utility is unavailable" }
        val extractAweme = shareUtil.asResolver().firstMethod {
            name = "extractAwemeFromSharePackage"
            parameters(packageClass)
            superclass()
        }
        val functionMembers = functionClass.resolve()
        val click = functionMembers.firstMethod { name = clickName.name; emptyParameters() }.self.apply { isAccessible = true }
        val abstractGetters = functionMembers.optional(silent = true).method { modifiers(Modifiers.ABSTRACT) }.map { it.self }
        check(abstractGetters.size == 4 && abstractGetters.all { it.parameterCount == 0 && it.returnType in listOf(String::class.java, Int::class.javaObjectType) }) {
            "Unsupported native share function contract"
        }
        check(abstractGetters.count { it.name == label.name } == 1) { "The share function has no single label getter" }
        val contextField = functionMembers.optional(silent = true).field { type { owner -> owner.declaredFields.any { it.type == packageClass } } }
            .singleOrNull()?.self?.apply { isAccessible = true } ?: error("The share function has no single context field")
        val nativeContext = contextField.type
        val contextPackage = field(nativeContext, packageClass)
        val contextArgs = field(nativeContext, argsClass)
        val contextDialog = field(nativeContext, Dialog::class.java)
        val contextActivity = field(nativeContext, Context::class.java)
        val moduleLoader = AndroidSharePanelEntries::class.java.classLoader
        val loader = object : ClassLoader(hostClassLoader) {
            override fun findClass(name: String): Class<*> = moduleLoader.loadClass(name)
        }
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Cannot create the share proxy code cache" }
        val proxyClass = ProxyBuilder.forClass(functionClass).parentClassLoader(loader).dexCache(cacheDirectory)
            .onlyMethods((abstractGetters + click).toTypedArray()).buildProxyClass()
        val constructor = proxyClass.resolve().firstConstructor { emptyParameters() }
        val active = AtomicBoolean(true)
        // Values never keep their weak keys, an Activity or the post.
        val panels = WeakHashMap<Dialog, Boolean>()
        val main = Handler(Looper.getMainLooper())
        scope.own(AutoCloseable {
            active.set(false)
            main.post { panels.keys.toList().forEach { runCatching { if (it.isShowing) it.dismiss() } }; panels.clear() }
        })
        scope.hooks.install(bind, HookSpec(
            id = "${entry.key}:Rows",
            requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
            before = { call ->
                if (!active.get()) return@HookSpec
                val panel = call.receiver as? Dialog ?: return@HookSpec
                val rows = call.argument(0) as? List<*> ?: return@HookSpec
                val firstRow = rows.firstOrNull() as? List<*> ?: return@HookSpec
                if (firstRow.any { it == null || !functionClass.isInstance(it) }) return@HookSpec
                val shareArgs = panelArgs.get(panel) ?: return@HookSpec
                val sharePackage = argsPackage.get(shareArgs) ?: return@HookSpec
                val aweme = extractAweme.invoke(sharePackage) ?: return@HookSpec
                val activity = panel.ownerActivity ?: activityOf(panel.context) ?: return@HookSpec
                if (activity.isFinishing || activity.isDestroyed) return@HookSpec
                val title = strings.get(activity, entry.title) ?: return@HookSpec logger.log("feature=$featureId share_entry_skipped no_title")
                val panelRef = WeakReference(panel)
                val packageRef = WeakReference(sharePackage)
                val function = constructor.create()
                ProxyBuilder.setInvocationHandler(function, InvocationHandler { proxy, method, values ->
                    try {
                        when {
                            method == click -> {
                                // A panel reused for another post must never open this post's details.
                                val current = panelRef.get()
                                val expected = packageRef.get()
                                if (active.get() && current != null && expected != null && current.isShowing &&
                                    argsPackage.get(panelArgs.get(current) ?: return@InvocationHandler true) === expected) {
                                    val currentAweme = extractAweme.invoke(expected)
                                    val currentActivity = current.ownerActivity ?: activityOf(current.context)
                                    if (currentAweme != null && currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
                                        entry.onClick(currentActivity, currentAweme, current::dismiss)
                                    }
                                }
                                true
                            }
                            method.name == label.name -> title
                            Modifier.isAbstract(method.modifiers) && method.returnType == String::class.java -> entry.key
                            Modifier.isAbstract(method.modifiers) && method.returnType == Int::class.javaObjectType -> entry.icon
                            else -> ProxyBuilder.callSuper(proxy, method, *(values ?: emptyArray()))
                        }
                    } catch (error: RuntimeException) {
                        logger.log("feature=$featureId share_entry_click_failed ${error.javaClass.simpleName}")
                        if (method == click) true else throw error
                    }
                })
                val state = checkNotNull(contextField.get(function)) { "The share function has no context" }
                contextPackage.set(state, sharePackage)
                contextArgs.set(state, shareArgs)
                contextDialog.set(state, panel)
                contextActivity.set(state, activity)
                // Both lists are copied: the host's lists may be immutable or shared with another panel.
                call.setArgument(0, rows.toMutableList().apply { this[0] = listOf(function) + firstRow })
                panels[panel] = true
            },
        ))
    }

    private fun field(owner: Class<*>, type: Class<*>): Field =
        owner.resolve().optional(silent = true).firstFieldOrNull { this.type = type }?.self?.apply { isAccessible = true }
            ?: error("${owner.name} has no ${type.name} field")

    private fun activityOf(context: Context): Activity? {
        var current: Context = context
        repeat(16) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }

    private companion object {
        const val SHARE_PACKAGE = "com.ss.android.ugc.aweme.sharer.ui.SharePackage"
    }
}

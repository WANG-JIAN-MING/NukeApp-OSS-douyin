package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.shared.dex.mapSearch
import io.github.nuke.oss.app.shared.dex.DESCRIPTOR_SEPARATOR
import io.github.nuke.oss.app.shared.dex.DexCache
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.settings.MemoryOptionStore
import io.github.nuke.oss.app.shared.settings.Option
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.encode
import io.github.nuke.oss.app.shared.settings.load
import io.github.nuke.oss.app.shared.settings.normalize
import io.github.nuke.oss.core.FeatureState
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.HookFailure
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.runtime.FeatureManager
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

// The pieces every feature contract test needs: a fake Hook backend, a feature manager on top of it, and a Dex
// resolver that answers each query with stand-in host methods.
abstract class HookHarness {
    protected val loader: ClassLoader = checkNotNull(javaClass.classLoader)
    protected val backend = FakeHookBackend()
    protected val failures = ArrayList<HookFailure>()
    protected val manager = FeatureManager("g", DefaultHookService(backend, HookFailureSink { failures += it }), Logger {})

    protected fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method = type.getDeclaredMethod(name, *parameters)

    protected fun resolver(vararg located: Pair<DexQuery, List<Method>>) = DexResolver(object : DexCache {
        override fun get(key: String): String? = null
        override fun put(key: String, descriptor: String) {}
    }, "scope", mapSearch { queries ->
        queries.mapValues { (_, query) ->
            located.first { it.first === query }.second.joinToString(DESCRIPTOR_SEPARATOR) { DexMethod(it).serialize() }
        }
    }, generatedDexQueries)

    // Like resolver, for features that also read fields or hook constructors: each query answers one member.
    protected fun resolverOf(vararg located: Pair<DexQuery, Any>) = DexResolver(object : DexCache {
        override fun get(key: String): String? = null
        override fun put(key: String, descriptor: String) {}
    }, "scope", mapSearch { queries ->
        queries.mapValues { (_, query) ->
            when (val found = located.first { it.first === query }.second) {
                is java.lang.reflect.Field -> DexField(found).serialize()
                is Method -> DexMethod(found).serialize()
                is java.lang.reflect.Constructor<*> -> DexMethod(found).serialize()
                else -> error("A query answers a Method, a Constructor or a Field")
            }
        }
    }, generatedDexQueries)

    // Option values as the host would read them after the user saved the given (already valid) values.
    protected fun values(featureId: String, options: List<Option>, vararg saved: Pair<String, Any>): OptionValues {
        val store = MemoryOptionStore()
        for ((key, value) in saved) {
            val option = options.first { it.key == key }
            store.write(featureId, key, option.encode(option.normalize(value)))
        }
        return store.load(featureId, options)
    }

    protected fun enable(feature: RuntimeFeature) {
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(status.reason, FeatureState.ACTIVE, status.state)
    }

    protected fun disable(feature: RuntimeFeature) = manager.setEnabled(feature.id, false)

    protected fun failsToInstall(feature: RuntimeFeature, fragment: String) {
        manager.register(feature)
        val status = manager.setEnabled(feature.id, true)
        assertEquals(FeatureState.FAILED, status.state)
        assertTrue(status.reason, status.reason!!.contains(fragment))
        assertEquals(0, backend.attachments.get())
    }
}

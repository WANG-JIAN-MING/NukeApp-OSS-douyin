package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Adds the chosen words to the danmaku block list whenever the host applies a danmaku configuration, past the 30 words
// Douyin allows. The words are only in the configuration the host reads; they are not sent to the server.
@HookEntry
internal class DanmakuBlockWords(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val configBeanName: String = CONFIG_BEAN,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.DANMAKU_CONFIG)
    private val words = options.lines(WORDS)

    override fun install(scope: FeatureScope) {
        if (words.isEmpty()) return
        val anchor = resolver.methods(hostClassLoader, OptionQueries.DANMAKU_CONFIG).single()
        val beanType = configBeanName.toClass(hostClassLoader)
        val receivers = anchor.declaringClass.resolve().optional(silent = true).method {
            isSynthetic = false
            returnType = Void.TYPE
            parameters(beanType)
        }.map { it.self }
        check(receivers.isNotEmpty()) { "No method taking ${beanType.simpleName} in ${anchor.declaringClass.name}" }
        val list = beanType.resolve().firstField { name = LIST_FIELD; superclass() }.self.apply { isAccessible = true }
        check(list.type == String::class.java) { "${beanType.name}.$LIST_FIELD is ${list.type.name}, expected String" }
        receivers.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "AddWords$index", before = { call ->
                val bean = call.argument(0)
                if (bean != null) {
                    val current = list.get(bean) as String?
                    val existing = if (current.isNullOrEmpty()) emptyList() else StringArrayJson.decode(current)
                    // A list in a shape this does not know is left as the host wrote it.
                    if (existing != null) list.set(bean, StringArrayJson.encode((existing + words).distinct()))
                }
            }))
        }
    }

    companion object : TikTokFeature {
        const val ID = "DanmakuBlockWords"
        const val WORDS = "words"
        const val CONFIG_BEAN = "com.ss.android.ugc.aweme.feed.danmaku.model.bean.ConfigContentBean"
        private const val LIST_FIELD = "blocking_word_list"
        val OPTIONS = listOf(LinesOption(WORDS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DanmakuBlockWords(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}

// The host keeps the block list as a JSON array of strings. Only that flat shape is read or written here.
internal object StringArrayJson {
    fun encode(items: List<String>): String = items.joinToString(",", "[", "]") { quote(it) }

    // Null when the text is not exactly one array of strings.
    fun decode(json: String): List<String>? {
        var at = 0
        fun skipSpace() { while (at < json.length && json[at].isWhitespace()) at++ }
        fun string(): String? {
            if (at >= json.length || json[at] != '"') return null
            at++
            val out = StringBuilder()
            while (at < json.length) {
                val c = json[at++]
                when {
                    c == '"' -> return out.toString()
                    c != '\\' -> out.append(c)
                    at >= json.length -> return null
                    else -> when (val escape = json[at++]) {
                        '"', '\\', '/' -> out.append(escape)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val code = json.substring(at, minOf(at + 4, json.length)).takeIf { it.length == 4 }?.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            at += 4
                        }
                        else -> return null
                    }
                }
            }
            return null
        }
        skipSpace()
        if (at >= json.length || json[at++] != '[') return null
        val items = ArrayList<String>()
        skipSpace()
        if (at < json.length && json[at] == ']') at++ else {
            while (true) {
                skipSpace()
                items += string() ?: return null
                skipSpace()
                if (at >= json.length) return null
                when (json[at++]) {
                    ',' -> continue
                    ']' -> break
                    else -> return null
                }
            }
        }
        skipSpace()
        return if (at == json.length) items else null
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

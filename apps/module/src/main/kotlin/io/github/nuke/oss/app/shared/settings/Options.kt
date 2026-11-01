package io.github.nuke.oss.app.shared.settings

// One user choice of a feature. Options are read once when the host starts, like the feature switch: a Hook
// callback never reads storage, and a changed value takes effect after the host restarts.
sealed interface Option {
    val key: String
}

private val KEY_PATTERN = Regex("[a-z0-9][a-z0-9-]*")

private fun requireKey(key: String) = require(KEY_PATTERN.matches(key)) { "Option key must be lowercase words: $key" }

// Choice IDs are stored joined by a comma, so they cannot contain one or a line break.
private fun requireChoices(choices: List<String>) {
    require(choices.isNotEmpty()) { "An option needs at least one choice" }
    require(choices.toSet().size == choices.size) { "Duplicate choice" }
    require(choices.none { it.isBlank() || ',' in it || '\n' in it }) { "A choice ID cannot be blank or contain a comma or line break" }
}

data class ToggleOption(override val key: String, val default: Boolean = false) : Option {
    init { requireKey(key) }
}

data class ChoiceOption(override val key: String, val choices: List<String>, val default: String = choices.first()) : Option {
    init {
        requireKey(key)
        requireChoices(choices)
        require(default in choices) { "Default $default is not a choice of $key" }
    }
}

data class MultiOption(override val key: String, val choices: List<String>, val default: Set<String> = emptySet()) : Option {
    init {
        requireKey(key)
        requireChoices(choices)
        require(choices.containsAll(default)) { "Default of $key has an unknown choice" }
    }
}

data class NumberOption(override val key: String, val default: Long, val min: Long, val max: Long) : Option {
    init {
        requireKey(key)
        require(min <= max && default in min..max) { "Default of $key is outside $min..$max" }
    }
}

// Several text entries, one per line: keyword lists and the like.
data class LinesOption(override val key: String, val default: List<String> = emptyList()) : Option {
    init { requireKey(key) }
}

// Chats picked from the host account's friends and groups, kept as conversation IDs one per line like LinesOption.
data class ConversationsOption(override val key: String) : Option {
    init { requireKey(key) }
}

// Friends picked from the host account's friend list, kept as user IDs one per line.
data class FriendsOption(override val key: String) : Option {
    init { requireKey(key) }
}

data class TextOption(override val key: String, val default: String = "", val maxLength: Int = 64) : Option {
    init {
        requireKey(key)
        require(maxLength > 0) { "maxLength must be positive" }
    }
}

// The values of one feature's options, as parsed from storage: always complete and always valid.
class OptionValues internal constructor(private val values: Map<String, Any>) {
    fun toggle(key: String): Boolean = typed(key)
    fun choice(key: String): String = typed(key)
    fun multi(key: String): Set<String> = typed(key)
    fun number(key: String): Long = typed(key)
    fun lines(key: String): List<String> = typed(key)
    fun conversations(key: String): List<String> = typed(key)
    fun friends(key: String): List<String> = typed(key)
    fun text(key: String): String = typed(key)

    internal operator fun get(option: Option): Any = values.getValue(option.key)

    private inline fun <reified T> typed(key: String): T =
        values[key] as? T ?: error("Option $key is not a ${T::class.simpleName} or does not exist")

    override fun equals(other: Any?) = other is OptionValues && other.values == values
    override fun hashCode() = values.hashCode()
    override fun toString() = values.toString()

    companion object {
        // The defaults, for a feature built without storage.
        fun defaults(options: List<Option>) = OptionValues(options.associate { it.key to it.defaultValue() })
    }
}

internal fun Option.defaultValue(): Any = when (this) {
    is ToggleOption -> default
    is ChoiceOption -> default
    is MultiOption -> default
    is NumberOption -> default
    is LinesOption -> default
    is ConversationsOption, is FriendsOption -> emptyList<String>()
    is TextOption -> default
}

private fun splitLines(raw: String): List<String> = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

// Reads a stored string. Anything unreadable, unknown or out of range gives the default or the nearest valid value,
// so a damaged store never stops a feature from installing.
internal fun Option.decode(raw: String?): Any {
    if (raw == null) return defaultValue()
    return when (this) {
        is ToggleOption -> raw.toBooleanStrictOrNull() ?: default
        is ChoiceOption -> raw.takeIf { it in choices } ?: default
        is MultiOption -> raw.split(',').filter { it in choices }.toSet()
        is NumberOption -> raw.toLongOrNull()?.coerceIn(min, max) ?: default
        is LinesOption, is ConversationsOption, is FriendsOption -> splitLines(raw)
        is TextOption -> raw.trim().take(maxLength)
    }
}

// Validates a value that came from the screen. A value of the wrong type or an unknown choice is a programming
// error and is rejected; text is trimmed and a number is clamped, the way the screen shows it.
internal fun Option.normalize(value: Any?): Any = when (this) {
    is ToggleOption -> value as? Boolean ?: throw IllegalArgumentException("Option $key takes a Boolean")
    is ChoiceOption -> (value as? String)?.takeIf { it in choices } ?: throw IllegalArgumentException("Option $key has no such choice")
    is MultiOption -> {
        val picked = (value as? Collection<*>)?.map { it as? String ?: throw IllegalArgumentException("Option $key takes strings") }
            ?: throw IllegalArgumentException("Option $key takes a set of strings")
        require(choices.containsAll(picked)) { "Option $key has no such choice" }
        picked.toSet()
    }
    is NumberOption -> when (value) {
        is Long -> value.coerceIn(min, max)
        is Int -> value.toLong().coerceIn(min, max)
        else -> throw IllegalArgumentException("Option $key takes a number")
    }
    is LinesOption, is ConversationsOption, is FriendsOption -> (value as? Collection<*>)
        ?.flatMap { splitLines(it as? String ?: throw IllegalArgumentException("Option $key takes strings")) }
        ?: throw IllegalArgumentException("Option $key takes a list of strings")
    is TextOption -> (value as? String)?.trim()?.take(maxLength)?.replace('\n', ' ')
        ?: throw IllegalArgumentException("Option $key takes a string")
}

// The stored form of a normalized value. Multi-choice values keep the order of the choices, so the same set always
// writes the same string.
internal fun Option.encode(value: Any): String = when (this) {
    is ToggleOption, is NumberOption, is ChoiceOption, is TextOption -> value.toString()
    is MultiOption -> choices.filter { it in (value as Set<*>) }.joinToString(",")
    is LinesOption, is ConversationsOption, is FriendsOption -> (value as List<*>).joinToString("\n")
}

// Where option values live. The host adapter backs it with the host's private storage.
interface OptionStore {
    fun read(featureId: String, key: String): String?
    fun write(featureId: String, key: String, value: String)
}

internal fun OptionStore.load(featureId: String, options: List<Option>): OptionValues =
    OptionValues(options.associate { it.key to it.decode(read(featureId, it.key)) })

// A feature's options as the settings screen needs them: what is stored, and what the running feature was built with.
data class OptionEntry(val option: Option, val saved: Any, val running: Any) {
    val pending: Boolean get() = saved != running
}

internal class FeatureOptions(val featureId: String, val options: List<Option>, val running: OptionValues) {
    init {
        require(options.map { it.key }.toSet().size == options.size) { "Duplicate option key in $featureId" }
    }
}

// Hands a feature its option values while the host registers features, and remembers what each one was built with so
// the settings screen can tell a saved value from the one that is running.
internal class OptionRegistry(private val store: OptionStore) {
    private val registered = ArrayList<FeatureOptions>()

    fun values(featureId: String, options: List<Option>): OptionValues {
        val running = store.load(featureId, options)
        registered += FeatureOptions(featureId, options, running)
        return running
    }

    fun features(): List<FeatureOptions> = registered.toList()
}

// Small values a feature keeps for itself across restarts, such as a daily counter. Unlike options they are written
// by the feature while it runs, never by the settings screen, and only outside Hook callbacks.
interface FeatureMemory {
    fun read(featureId: String, key: String): String?
    fun write(featureId: String, key: String, value: String)
}

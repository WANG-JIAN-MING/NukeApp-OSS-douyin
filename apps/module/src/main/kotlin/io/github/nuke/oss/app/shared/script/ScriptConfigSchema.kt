package io.github.nuke.oss.app.shared.script

import io.github.nuke.oss.app.shared.data.MiniJson

// The settings a script declares in its configuration schema. The package reader validated the schema; this reads it
// back for the settings page and checks the values the user enters.
internal data class ScriptConfigSchema(val fields: List<ConfigField>) {
    // The saved values the schema still accepts, with every missing or no longer valid key at its default. Keys the
    // schema no longer has are dropped, so a script never sees settings from an older version it does not declare.
    fun values(saved: Map<String, Any?>): Map<String, Any?> = buildMap {
        for (field in fields) {
            val value = saved[field.key]?.takeIf { field.problem(it) == null } ?: field.default
            if (value != null) put(field.key, value)
        }
    }

    companion object {
        val EMPTY = ScriptConfigSchema(emptyList())

        fun parse(text: String): ScriptConfigSchema {
            val root = MiniJson.obj(MiniJson.parse(text)) ?: throw IllegalArgumentException("The schema is not an object")
            val fields = MiniJson.array(root["fields"]) ?: throw IllegalArgumentException("The schema has no fields")
            return ScriptConfigSchema(fields.map { item ->
                val field = MiniJson.obj(item) ?: throw IllegalArgumentException("A schema field is not an object")
                ConfigField(
                    key = field["key"] as String,
                    type = ConfigType.of(field["type"] as String),
                    title = field["title"] as String,
                    description = field["description"] as? String,
                    default = field["default"],
                    options = MiniJson.array(field["options"]).orEmpty().map { choice ->
                        val option = MiniJson.obj(choice)!!
                        ConfigChoice(option["value"] as String, option["label"] as String)
                    },
                    min = (field["min"] as? Number)?.toDouble(),
                    max = (field["max"] as? Number)?.toDouble(),
                    maxLength = (field["maxLength"] as? Number)?.toInt(),
                )
            })
        }
    }
}

internal enum class ConfigType(val key: String) {
    STRING("string"), TEXT("text"), NUMBER("number"), INTEGER("integer"), BOOLEAN("boolean"), SELECT("select");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown field type $key")
    }
}

internal data class ConfigChoice(val value: String, val label: String)

internal data class ConfigField(
    val key: String,
    val type: ConfigType,
    val title: String,
    val description: String?,
    val default: Any?,
    val options: List<ConfigChoice>,
    val min: Double?,
    val max: Double?,
    val maxLength: Int?,
) {
    // Why value does not fit, or null. Numbers arrive as Long or Double from JSON and from the editors alike.
    fun problem(value: Any?): ConfigProblem? = when (type) {
        ConfigType.BOOLEAN -> if (value is Boolean) null else ConfigProblem.TYPE
        ConfigType.STRING, ConfigType.TEXT -> when {
            value !is String -> ConfigProblem.TYPE
            maxLength != null && value.length > maxLength -> ConfigProblem.TOO_LONG
            else -> null
        }
        ConfigType.SELECT -> if (options.any { it.value == value }) null else ConfigProblem.TYPE
        ConfigType.NUMBER, ConfigType.INTEGER -> {
            val number = (value as? Number)?.toDouble()
            when {
                number == null || number.isNaN() || number.isInfinite() -> ConfigProblem.TYPE
                type == ConfigType.INTEGER && number % 1.0 != 0.0 -> ConfigProblem.TYPE
                min != null && number < min -> ConfigProblem.BELOW_MIN
                max != null && number > max -> ConfigProblem.ABOVE_MAX
                else -> null
            }
        }
    }
}

internal enum class ConfigProblem { TYPE, TOO_LONG, BELOW_MIN, ABOVE_MAX }

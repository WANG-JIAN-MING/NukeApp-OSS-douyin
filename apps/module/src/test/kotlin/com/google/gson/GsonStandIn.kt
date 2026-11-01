package com.google.gson

// Stand-ins for the host's Gson, which the JVM tests do not have: building one counts, and can be made to fail the way
// it does while the host's class loader is still being created.
class Gson {
    fun toJson(value: Any): String = "json:$value"
}

class GsonBuilder {
    fun setPrettyPrinting(): GsonBuilder = this

    fun create(): Gson {
        built++
        if (failing) throw IllegalStateException("host adapters are not ready")
        return Gson()
    }

    companion object {
        @JvmStatic var built = 0
        @JvmStatic var failing = false
    }
}

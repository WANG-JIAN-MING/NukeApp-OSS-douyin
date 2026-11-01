package com.google.gson.annotations

// A stand-in for Gson's annotation, which the host keeps on its model fields; only the members the reader asks for.
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class SerializedName(val value: String, val alternate: Array<String> = [])

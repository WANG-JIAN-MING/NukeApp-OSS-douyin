package io.github.nuke.oss.script.java

class Sample(var name: String) {
    @JvmField var count: Int = 0
    @Suppress("unused") private val secret = "hidden"

    fun greet(other: String) = "hi $other from $name"
    fun pick(value: Int) = "int:$value"
    fun pick(value: Double) = "double:$value"
    fun pick(value: String) = "string:$value"
    fun pick(value: Sample) = "sample:${value.name}"
    fun same(a: Long) = a
    fun fail(): String = throw IllegalStateException("boom")
    fun items(): List<String> = listOf("a", "b")
    fun bytes() = byteArrayOf(1, 2, 3)
    fun sum(values: IntArray) = values.sum()
    fun self() = this
    fun nothing() {}

    companion object {
        @JvmField val CONSTANT = "const"
        @JvmStatic fun create(name: String) = Sample(name)
    }
}

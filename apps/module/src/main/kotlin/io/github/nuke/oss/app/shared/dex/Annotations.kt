package io.github.nuke.oss.app.shared.dex

import org.luckypray.dexkit.query.matchers.AnnotationElementMatcher
import org.luckypray.dexkit.query.matchers.AnnotationMatcher
import org.luckypray.dexkit.query.matchers.AnnotationsMatcher
import org.luckypray.dexkit.query.matchers.ClassMatcher

// A class that carries an annotation with this string element. Settings keys and Kotlin coroutine metadata name
// the class they belong to this way, which survives renaming where the class name itself does not.
fun ClassMatcher.annotatedWith(value: String, element: String? = null): ClassMatcher {
    val matcher = AnnotationElementMatcher.create()
    if (element != null) matcher.name(element)
    return annotations(AnnotationsMatcher.create().add(AnnotationMatcher.create().addElement(matcher.stringValue(value))))
}

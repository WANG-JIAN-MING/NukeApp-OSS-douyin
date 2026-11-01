package io.github.nuke.oss.app.shared.activity

internal data class InfoField(val label: String, val value: String)

internal data class InfoSection(val title: String, val fields: List<InfoField>)

// What an information sheet shows, with every text already resolved: the sections, what a search leaves of them, and
// the full text that copy-all and save use whatever the search shows. footer is a note under the list, such as how
// many entries were left out.
internal class InfoSheet(
    val title: String,
    val sections: List<InfoSection>,
    val fullText: String,
    val search: (query: String) -> List<InfoSection>,
    val searchHint: String,
    val hint: String,
    val empty: String,
    val noMatch: String,
    val footer: String? = null,
)

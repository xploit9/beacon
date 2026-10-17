package dev.hermes.android.ui

internal fun settingsLabel(route: String): String = if (route == "Settings") "Chat preferences" else route

private val searchWhitespace = Regex("\\s+")
private fun searchTermsMatch(text: String, query: String): Boolean =
    query.splitToSequence(searchWhitespace).filter(String::isNotBlank).all { text.contains(it, ignoreCase = true) }

internal fun settingsSearchMatches(visibleLabel: String, description: String, query: String): Boolean =
    searchTermsMatch("$visibleLabel $description", query)

internal fun modelSearchMatches(provider: String, id: String, label: String, query: String): Boolean =
    searchTermsMatch("$provider $id $label", query)

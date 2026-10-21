package dev.hermes.android.data
import dev.hermes.android.core.Draft

/** Evict only the exact successfully persisted value, never newer edits or failed checkpoints. */
internal fun pruneSavedDrafts(drafts: MutableMap<String, Draft>, saved: MutableMap<String, String>, activeKey: String?) {
    val removable = drafts.filter { (key, value) -> key != activeKey && saved[key] == value.encode() }.keys
    removable.forEach { drafts.remove(it); saved.remove(it) }
    saved.keys.retainAll(drafts.keys)
}

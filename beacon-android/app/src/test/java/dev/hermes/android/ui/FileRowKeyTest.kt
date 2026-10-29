package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileRowKeyTest {
    @Test fun aWindowsShortcutFolderAndItsTargetGetDifferentKeys() {
        // Hermes lists the My Documents shortcut under the path of Documents; the same key twice crashed the Files list (29 Oct 2026).
        val documents = json("name" to "Documents", "path" to "C:/Users/owner/Documents", "is_directory" to true)
        val shortcut = json("name" to "My Documents", "path" to "C:/Users/owner/Documents", "is_directory" to true)
        assertNotEquals(fileRowKey(documents), fileRowKey(shortcut))
    }
}

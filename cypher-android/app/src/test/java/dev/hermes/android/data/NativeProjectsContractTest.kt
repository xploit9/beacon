package dev.hermes.android.data

import dev.hermes.android.core.json
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeProjectsContractTest {
    @Test fun deletionMustHaveAnActualNativeListing() {
        assertFalse(nativeProjectRemovalConfirmed(json("ok" to true), "p_owned"))
        assertFalse(nativeProjectRemovalConfirmed(json("projects" to "not a list"), "p_owned"))
        assertFalse(nativeProjectRemovalConfirmed(json("projects" to JSONArray().put(json("name" to "No identity"))), "p_owned"))
        assertFalse(nativeProjectRemovalConfirmed(json("projects" to JSONArray().put(json("id" to "p_owned"))), "p_owned"))
        assertTrue(nativeProjectRemovalConfirmed(json("projects" to JSONArray()), "p_owned"))
        assertTrue(nativeProjectRemovalConfirmed(json("projects" to JSONArray().put(json("id" to "p_other"))), "p_owned"))
    }
    @Test fun nativeFolderReadbackRespectsHostPathSemantics() {
        assertTrue(sameNativeFolder("C:\\Work\\Project\\", "c:/work/project"))
        assertTrue(sameNativeFolder("/work/project/", "/work/project"))
        assertFalse(sameNativeFolder("/work/Project", "/work/project"))
        assertFalse(sameNativeFolder("", ""))
    }
}

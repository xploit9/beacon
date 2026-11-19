package dev.hermes.android.voice

import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.loginLiveTest
import dev.hermes.android.preferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.rules.ExternalResource
import java.io.File

/**
 * The demo sign-in of a call device test, staged before the app's first screen (use it as the rule with the lower order, outside
 * the compose rule). The staged demo login (device-test.py --isolated / demo-fixture.py prepare) signs in and the phone counts as
 * set up, so the app opens on the chat instead of the setup wizard a fresh sign-in would show over it. demo-fixture.py restore
 * puts the phone's own sign-in and preferences back. Without the [optIn] argument or a staged login it does nothing.
 */
class DemoCallSignIn(private val optIn: String = "beaconLiveService") : ExternalResource() {
    var origin = ""; private set
    val staged get() = origin.isNotBlank()

    override fun before() {
        if (InstrumentationRegistry.getArguments().getString(optIn) != "1") return
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val file = File(app.filesDir, "live-auth.json")
        if (!file.isFile) return
        val auth = JSONObject(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        check(file.delete()) { "The staged login must not stay on the phone" }
        val address = auth.getString("origin")
        require(Uri.parse(address).let { it.scheme == "https" && it.port == 8446 }) { "Call device tests run only against the demo Hermes" }
        runBlocking {
            loginLiveTest(app, address, auth)
            app.preferences.edit {
                it[stringPreferencesKey("origin")] = address
                it[booleanPreferencesKey("setup_complete")] = true
                it[booleanPreferencesKey("setup_active")] = false
                it[stringPreferencesKey("setup_step")] = "done"
            }
        }
        origin = address
    }
}

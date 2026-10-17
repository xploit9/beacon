package dev.hermes.android.data

import dev.hermes.android.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly opted-in check against the deployed native dashboard; no credentials in fixtures. */
class NativeAuthLiveTest {
    @Test(timeout = 120_000) fun nativeLoginProfilesAndReleaseUseTheDeployedDashboard() = runBlocking<Unit> {
        val credentialsPath = System.getenv("CYPHER_LIVE_CREDENTIAL_FILE")
        assumeTrue("Live credentials are supplied only for deployment verification", !credentialsPath.isNullOrBlank())
        val origin = System.getenv("CYPHER_LIVE_ORIGIN").orEmpty()
        require(origin.startsWith("https://") && origin == BuildConfig.DEFAULT_ORIGIN)
        val credentials = JSONObject(File(credentialsPath!!).readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val client = CypherClient(AuthContext(origin, emptyList()))
        val boot = client.login(credentials.getString("password"), username = credentials.getString("username"))
        assertEquals("hermes-dashboard", boot.getString("backend"))
        assertEquals(credentials.getString("username"), client.auth.owner)
        assertTrue(client.auth.cookies.isNotEmpty())
        for (profile in listOf("default", "academic", "coder", "cybercb")) {
            val scoped = CypherClient(client.auth.copy(profile = profile))
            val scopedBoot = scoped.bootstrap()
            assertEquals(profile, scopedBoot.getString("profile"))
            assertEquals(client.auth.owner, scoped.auth.owner)
        }
        val release = client.nativeRequest("/api/plugins/cypher/mobile/releases/latest")
        assertEquals(2, release.getInt("min_contract_version"))
        assertEquals("dev.hermes.android", release.getString("package_name"))
        assertTrue(release.getLong("size_bytes") > 0)
    }
}

package dev.hermes.android

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.core.digest
import dev.hermes.android.core.string
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.push.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Android system notifications only, synthetic identities; no service/model/mail/network call. */
class NativeActivityDeviceTest {
    @Test fun ongoingRequestHasPrivateActionsFallbackAndLifecycle() = exercise("approval")
    @Test fun questionOffersChoicesAndFreeTextWithPrivateLifecycle() = exercise("clarify")
    @Test fun fetchedTestAndFailurePreviewsStayArabicAndChatWordsStayVerbatim() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val manager = app.getSystemService(NotificationManager::class.java)
        val fixture = NativeExperienceFixture()
        val scope = fixture.scope
        val prefs = app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE)
        val source = "synthetic-preview-source"
        val key = "source:${source.digest()}"
        val oldBinding = prefs.getString(key, null)
        val oldAuth = app.auth.read(scope.origin)
        val oldFactory = KeepConnectedService.clientFactoryForTest
        val oldLocale = java.util.Locale.getDefault()
        val tags = mutableListOf<String>()
        fun fetched(kind: String, title: String, body: String): Pair<String, String> {
            val route = fixture.emitTaskResult(conversationId = "chat-b").apply {
                put("source_ref", source); put("kind", kind); put("preview", json("title" to title, "body" to body))
            }
            val event = route.string("event_id")
            val conversation = route.string("conversation_ref")
            val tag = "$source\u0000$event".digest()
            tags += tag
            val data = mapOf("source_ref" to source, "event_id" to event, "conversation_ref" to conversation, "kind" to kind)
            assertNotNull(receivePush(app, data, null, deferPreview = true))
            fun posted() = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == 1 }?.notification
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (posted() == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertNotNull(posted())
            Thread.sleep(600) // Android rate-limits repeated notification updates.
            runBlocking { refreshNotificationPreview(app, source, event, conversation, kind) }
            val updated = android.os.SystemClock.elapsedRealtime() + 5_000
            while (posted()?.flags?.and(Notification.FLAG_ONLY_ALERT_ONCE) == 0 && android.os.SystemClock.elapsedRealtime() < updated) Thread.sleep(25)
            val notice = requireNotNull(posted())
            assertTrue("The authenticated preview was posted", notice.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
            return notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString() to notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        }
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"))
            app.auth.write(fixture.client.auth.copy(bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture")))
            KeepConnectedService.clientFactoryForTest = { current, store -> fixture.clientFor(current, store) }
            val binding = json("source" to source, "origin" to scope.origin, "owner" to scope.owner, "profile" to scope.profile, "native_provider" to scope.nativeProvider, "backend" to "hermes-dashboard", "target" to "fixture-target")
            assertTrue(prefs.edit().putString(key, android.util.Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), key), android.util.Base64.NO_WRAP)).commit())
            val failureTitle = "Beacon work failed"
            val failureBody = "Open Beacon to see what went wrong."
            val actual = listOf(
                fetched("completion", failureTitle, failureBody),
                fetched("test", "Beacon notification test", "Synthetic test. Seeing this notification confirms arrival on this phone."),
                fetched("failure", failureTitle, failureBody),
            )
            assertEquals(listOf(failureTitle to failureBody, "Beacon · اختبار الإشعارات" to "تم استلام إشعار الاختبار.",
                "Beacon · تعذّر إكمال العمل" to "افتح Beacon لمعرفة سبب المشكلة."), actual)
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            assertEquals(failureTitle to failureBody, fetched("failure", failureTitle, failureBody))
        } finally {
            tags.forEach { manager.cancel(it, 1) }
            java.util.Locale.setDefault(oldLocale)
            KeepConnectedService.clientFactoryForTest = oldFactory
            prefs.edit().putString(key, oldBinding).commit()
            app.auth.write(oldAuth)
        }
    }

    @Test fun pluginQuestionAndApprovalUseThePhoneLanguage() {
        val prior = java.util.Locale.getDefault()
        val language = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("native_ui_language", "ar")
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag(language))
            for (kind in listOf("clarify", "approval")) exercise(kind, translated = true)
        } finally { java.util.Locale.setDefault(prior) }
    }

    private fun exercise(kind:String, translated: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val source = "synthetic-activity-source-$kind"
        val event = "synthetic-approval-event"
        val activity = "synthetic-activity-${System.currentTimeMillis()}"
        val origin = "https://notification-fixture.invalid"
        val previousAuth = context.auth.read(origin)
        val auth = AuthContext(origin, emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard", nativeProvider = "fixture")
        val prefs = context.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE)
        val key = "source:${source.digest()}"
        val route = json("kind" to kind,"text_reply" to (kind=="clarify"),"preview" to json("complete" to true,"title" to "Beacon needs approval", "body" to "Read the synthetic test folder"),
            "actions" to JSONArray(listOf("once", "session", "always", "deny").map { json("value" to it, "label" to it) }))
        try {
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000+120, route)
            assertTrue("An unregistered source cannot post", manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            context.auth.write(auth)
            val binding = json("source" to source, "origin" to origin, "owner" to auth.owner, "profile" to auth.profile, "native_provider" to auth.nativeProvider, "backend" to "hermes-dashboard")
            assertTrue(prefs.edit().putString(key, android.util.Base64.encodeToString(context.cipher.encrypt(binding.toString().toByteArray(), key), android.util.Base64.NO_WRAP)).commit())
            val arabic = translated && java.util.Locale.getDefault().language == "ar"
            val question = kind == "clarify"
            val title = if (question) "Question for you" else "Approval needed"
            val body = if (question) "Open Beacon to answer." else "Open Beacon to review the request."
            if (translated) {
                route.getJSONObject("preview").put("title", title).put("body", body).put("complete", false)
                route.put("actions", JSONArray()) // The plugin's fixed preview has no disclosed answers.
            }
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000+120, route)
            val deadline = System.currentTimeMillis()+3000
            while (manager.activeNotifications.none { it.notification.extras.getString("native_source") == source } && System.currentTimeMillis()<deadline) Thread.sleep(20)
            val posted = manager.activeNotifications.single { it.notification.extras.getString("native_source") == source }
            val notification = posted.notification
            if (translated) {
                val expectedTitle = if (arabic) (if (question) "سؤال لك" else "موافقة مطلوبة") else title
                val expectedBody = if (arabic) (if (question) "افتح Beacon للإجابة." else "افتح Beacon لمراجعة الطلب.") else body
                assertEquals(expectedTitle, notification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(expectedBody, notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(if (arabic) "مراجعة" else "Review", notification.actions.last().title.toString())
                assertEquals(if (arabic) "نشاط Beacon" else "Beacon activity", notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(if (arabic) "افتح Beacon للمراجعة." else "Open Beacon to review.", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
                if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("capture_alert") == "1") {
                    captureAlert(context, source, expectedTitle, expectedBody, kind)
                }
                return
            }
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Urgent requests must be eligible for heads-up popups",NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(notification.channelId).importance)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("Read the synthetic test folder", notification.extras.getString(Notification.EXTRA_TEXT))
            // The plugin's fixed title and button label are shown in the app's words (slop list rows 10.14, 10.15); a question's own choices stay as written.
            assertEquals("Approval needed", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Question for you", dev.hermes.android.push.activityTitleWords(context, "Beacon asks"))
            assertEquals("Allow for this chat", dev.hermes.android.push.activityLabelWords(context, "approval", "Allow session"))
            assertEquals("Allow session", dev.hermes.android.push.activityLabelWords(context, "clarify", "Allow session"))
            assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("folder"))
            assertEquals(if(kind=="approval") listOf("once", "session", "deny") else listOf("once","session","Answer"), notification.actions.map { it.title.toString() })
            if(kind=="clarify") assertEquals("answer",notification.actions.last().remoteInputs.single().resultKey)
            assertTrue(notification.actions.all { it.actionIntent.creatorPackage == context.packageName })
            if(Build.VERSION.SDK_INT>=31) assertTrue(notification.actions.all { it.isAuthenticationRequired })
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
                if(manager.canPostPromotedNotifications()) assertTrue(notification.hasPromotableCharacteristics())
            }
            route.getJSONObject("preview").put("complete",false)
            presentNativeActivity(context,source,event,activity,kind,System.currentTimeMillis()/1000+120,route)
            val incompleteDeadline=System.currentTimeMillis()+3000
            while(manager.activeNotifications.firstOrNull { it.notification.extras.getString("native_source")==source }?.notification?.actions?.none { it.title.toString()=="Review" }==true&&System.currentTimeMillis()<incompleteDeadline) Thread.sleep(20)
            val incomplete=manager.activeNotifications.single { it.notification.extras.getString("native_source")==source }.notification
            assertEquals(if(kind=="approval") listOf("deny","Review") else listOf("Review"),incomplete.actions.map { it.title.toString() })
            assertTrue("A hidden question must not offer blind text input",incomplete.actions.all { it.remoteInputs.isNullOrEmpty() })
            presentNativeActivity(context, source, "synthetic-settled-event", activity, "activity_end", System.currentTimeMillis()/1000+120)
            val cancelledDeadline = System.currentTimeMillis()+3000
            while (manager.activeNotifications.any { it.notification.extras.getString("native_source") == source } && System.currentTimeMillis()<cancelledDeadline) Thread.sleep(20)
            assertTrue("Android must remove the settled activity", manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            // A delayed authenticated preview or duplicate push cannot resurrect an answered request.
            presentNativeActivity(context,source,event,activity,kind,System.currentTimeMillis()/1000+120,route)
            Thread.sleep(100)
            assertTrue("Settled activity must remain gone after a late refresh",manager.activeNotifications.none { it.notification.extras.getString("native_source")==source })
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000-1, route)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            java.io.File(context.filesDir, "native-activity-probe.json").writeText(json("sdk" to Build.VERSION.SDK_INT, "actions" to JSONArray(notification.actions.map { it.title.toString() }), "private_visibility" to (notification.visibility == Notification.VISIBILITY_PRIVATE), "promotion_allowed" to if(Build.VERSION.SDK_INT>=36) manager.canPostPromotedNotifications() else false, "promotion_requested" to notification.extras.getBoolean("android.requestPromotedOngoing"), "promotable" to if(Build.VERSION.SDK_INT>=36) notification.hasPromotableCharacteristics() else false, "unauthorized_rejected" to true, "expired_rejected" to true, "settled_removed" to true).toString())
        } finally { cancelNativeActivities(context, source); prefs.edit().remove(key).commit(); context.auth.write(previousAuth) }
    }

    private fun captureAlert(context: BeaconApplication, source: String, title: String, body: String, kind: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val service = automation.serviceInfo
        val flags = service.flags
        service.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        try {
            shell("cmd statusbar expand-notifications")
            Thread.sleep(2_000)
            val label = automation.windows.firstNotNullOfOrNull { it.root?.findAccessibilityNodeInfosByText(title)?.firstOrNull { node -> node.text?.toString() == title } }
            val row = label?.let { generateSequence(it) { node -> node.parent }.firstOrNull { node -> node.isClickable && node.findAccessibilityNodeInfosByText(body).isNotEmpty() } }
            val screen = requireNotNull(automation.takeScreenshot())
            val bounds = android.graphics.Rect()
            row?.getBoundsInScreen(bounds)
            val image = if (bounds.intersect(0, 0, screen.width, screen.height)) {
                android.graphics.Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
            } else {
                // Some SystemUI versions omit notification rows from accessibility. Capture the shade only on the clean fixture device.
                val keys = shell("cmd notification list").lineSequence().filter(String::isNotBlank).map { it.split('|') }.toList()
                check(keys.any { it.getOrNull(1) == context.packageName } && keys.all { key ->
                    key.getOrNull(1) == context.packageName || key.getOrNull(1) == "android" && key.getOrNull(2) == "19" ||
                        key.getOrNull(1) == "dev.hermes.android.stable" && key.getOrNull(2) == "4082" && key.getOrNull(3) == "null" // Keep connected
                })
                check(context.getSystemService(NotificationManager::class.java).activeNotifications.all { it.notification.extras.getString("native_source") == source })
                screen
            }
            val dir = java.io.File(context.filesDir, "native-activity").apply { mkdirs() }
            java.io.File(dir, "${java.util.Locale.getDefault().language}-$kind.png").outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            if (image !== screen) image.recycle()
            screen.recycle()
        } finally {
            shell("cmd statusbar collapse")
            service.flags = flags
            automation.serviceInfo = service
        }
    }
}

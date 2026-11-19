// Dataset.Builder(RemoteViews) and setValue(id, value, RemoteViews) are what spikes S8-S9 measured on Android 16.
@file:Suppress("DEPRECATION")

package dev.hermes.agentphone.helper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import dev.hermes.agentphone.helper.core.Answer
import dev.hermes.agentphone.helper.core.Fills
import dev.hermes.agentphone.helper.core.FormField
import dev.hermes.agentphone.helper.core.Offer
import dev.hermes.agentphone.helper.core.Taken

/**
 * Dataset authentication for Beacon's entry (contract §6.2). Picking the entry spends the arm: the helper sends one
 * release_request with this request's own context and waits up to 8 s for the host's release or deny. A code the
 * helper read from a notification is filled here without asking the host. Values go straight to Android and are dropped.
 */
class AuthActivity : Activity() {
    @Volatile private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = Runtime.of(this)
        val req = intent.getStringExtra(EXTRA_REQ)
        val taken = if (req == null) Taken.Unknown else runtime.fills.take(req)
        when (taken) {
            Taken.Unknown -> finishWith(null)
            is Taken.Stale -> {
                runtime.sessions.event(failed(taken.offer, "cancelled"))
                finishWith(null)
            }
            is Taken.Ready -> {
                val offer = taken.offer
                val code = offer.arm.code
                if (code != null) {
                    val filled = Fills.valuesFor(offer, mapOf("otp" to code))
                    val n = finishWith(filled)
                    runtime.sessions.event(linkedMapOf(
                        "t" to "code_filled", "arm_id" to offer.arm.armId, "fields" to n,
                        "targets" to if (n > 0) Fills.targets(filled) else emptyList<Any?>(),
                    ))
                } else {
                    Thread({ release(runtime, offer) }, "beacon-release").start()
                }
            }
        }
    }

    private fun release(runtime: Runtime, offer: Offer) {
        val asked = runtime.sessions.eventNow(linkedMapOf(
            "t" to "release_request", "req" to offer.req, "arm_id" to offer.arm.armId, "item_id" to offer.item.itemId,
            "roles" to offer.roles, "context" to offer.context,
        ))
        val answer = if (asked) runtime.fills.await(offer.req, RELEASE_WAIT_MS) else Answer.Cancelled
        runOnUiThread {
            when (answer) {
                is Answer.Release -> {
                    val n = finishWith(Fills.valuesFor(offer, answer.values))
                    runtime.sessions.event(linkedMapOf("t" to "filled", "req" to offer.req, "arm_id" to offer.arm.armId, "fields" to n))
                }
                Answer.Deny -> { runtime.sessions.event(failed(offer, "denied")); finishWith(null) }
                null -> { runtime.sessions.event(failed(offer, "timeout")); finishWith(null) }
                Answer.Cancelled -> { runtime.sessions.event(failed(offer, "cancelled")); finishWith(null) }
            }
        }
    }

    private fun failed(offer: Offer, reason: String) =
        linkedMapOf<String, Any?>("t" to "fill_failed", "req" to offer.req, "arm_id" to offer.arm.armId, "reason" to reason)

    /** Hands the values to Android (or cancels) and finishes; returns how many fields were filled. */
    private fun finishWith(values: List<Pair<FormField, String>>?): Int {
        if (done) return 0
        done = true
        var n = 0
        if (values.isNullOrEmpty()) {
            setResult(RESULT_CANCELED)
        } else {
            val presentation = RemoteViews(packageName, R.layout.item).apply { setTextViewText(R.id.text, getString(R.string.app_name)) }
            val dataset = Dataset.Builder(presentation)
            for ((field, value) in values) {
                dataset.setValue(field.handle as AutofillId, AutofillValue.forText(value), presentation)
                n++
            }
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset.build()))
        }
        Log.i(Runtime.TAG, "auth result fields=$n")
        finish()
        return n
    }

    companion object {
        const val EXTRA_REQ = "req"
        const val RELEASE_WAIT_MS = 8000L
    }
}

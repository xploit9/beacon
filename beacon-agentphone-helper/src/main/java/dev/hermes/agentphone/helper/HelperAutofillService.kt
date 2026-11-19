// Dataset.Builder(RemoteViews) and setValue(id, value, RemoteViews) are what spikes S8-S9 measured on Android 16.
@file:Suppress("DEPRECATION")

package dev.hermes.agentphone.helper

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import dev.hermes.agentphone.helper.core.FormField
import dev.hermes.agentphone.helper.core.Roles
import java.util.regex.Pattern

/**
 * Android's Autofill service, labelled "Beacon" (EMU-DESIGN §6.3-6.4, contract §6). On every fill request it sends the
 * host a fill context (package, certificates, web domain, field ids and roles; never field text); while an arm matches
 * it offers one "Beacon · <label>" entry behind dataset authentication (AuthActivity fetches the values); forms with a
 * password field get Android's save prompt, whose result goes to the host over the link and nowhere else.
 */
class HelperAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        try {
            val runtime = Runtime.of(this)
            val structure = request.fillContexts.last().structure
            val pkg = structure.activityComponent.packageName
            val scan = Scan(structure, withValues = false)
            val context = runtime.fills.context(pkg, runtime.certs(pkg), runtime.source(pkg), scan.fields, scan.outerDomain, scan.outerScheme)
            runtime.sessions.event(linkedMapOf("t" to "fill_context", "context" to context.map))
            val response = FillResponse.Builder()
            var any = false
            for (offer in runtime.fills.offer(pkg, context)) {
                val presentation = presentation(getString(R.string.entry, offer.item.label))
                val dataset = Dataset.Builder(presentation)
                // Shown whatever 1-3 characters the field holds: AOSP FillUi.ViewItem.matches never shows a dataset with an explicitly
                // null filter, or with no filter, no value and authentication (ours), under typed text; a pattern matching any text
                // keeps it. Above 3 characters Android shows no dropdown at all (max_input_length_for_autofill), so the plugin's
                // driver empties a field the item fills before asking (EMU-JOIN fu2, 18 Nov).
                for (field in offer.fields) dataset.setValue(field.handle as AutofillId, null, ANY_TEXT, presentation)
                val auth = PendingIntent.getActivity(
                    this, offer.req.hashCode(),
                    // The identifier keeps each entry's PendingIntent distinct (extras alone don't).
                    Intent(this, AuthActivity::class.java).setIdentifier(offer.req).putExtra(AuthActivity.EXTRA_REQ, offer.req),
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                dataset.setAuthentication(auth.intentSender)
                response.addDataset(dataset.build())
                any = true
            }
            saveInfo(context.fields)?.let { response.setSaveInfo(it); any = true }
            Log.i(Runtime.TAG, "fill request fields=${context.fields.size} answered=$any")
            callback.onSuccess(if (any) response.build() else null)
        } catch (e: RuntimeException) {
            Log.i(Runtime.TAG, "fill request failed ${e.javaClass.simpleName}")
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val runtime = Runtime.of(this)
            val structure = request.fillContexts.last().structure
            val pkg = structure.activityComponent.packageName
            val scan = Scan(structure, withValues = true)
            val context = runtime.fills.context(
                pkg, runtime.certs(pkg), runtime.source(pkg), scan.fields, scan.outerDomain, scan.outerScheme, record = false,
            )
            val values = linkedMapOf<String, Any?>()
            for (role in listOf("username", "password", "new_password")) {
                context.fields.firstOrNull { it.role == role }?.let { f -> scan.values[f.id]?.takeIf { it.isNotEmpty() }?.let { values[role] = it } }
            }
            val sent = runtime.sessions.isLive
            if (sent) {
                runtime.sessions.event(linkedMapOf("t" to "save", "save_id" to runtime.newId(), "context" to context.map, "values" to values))
            }
            Log.i(Runtime.TAG, "save request delivered=$sent")
            if (sent) callback.onSuccess() else callback.onFailure(getString(R.string.save_offline))
        } catch (e: RuntimeException) {
            callback.onFailure(getString(R.string.save_offline))
        }
    }

    private fun presentation(text: String) = RemoteViews(packageName, R.layout.item).apply { setTextViewText(R.id.text, text) }

    private fun saveInfo(fields: List<FormField>): SaveInfo? {
        val passwords = fields.filter { it.role == "password" || it.role == "new_password" }.map { it.handle as AutofillId }
        if (passwords.isEmpty()) return null
        val users = fields.filter { it.role == "username" }.map { it.handle as AutofillId }
        val builder = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME, passwords.toTypedArray())
        if (users.isNotEmpty()) builder.setOptionalIds(users.toTypedArray())
        return builder.build()
    }

    private companion object {
        /** Matches any typed text (FillUi lowercases it and calls matches()); DOTALL for text with line breaks. */
        val ANY_TEXT: Pattern = Pattern.compile(".*", Pattern.DOTALL)
    }

    /** The form fields of one structure: id, role, focus, web domain and size; values only for a save. */
    private class Scan(structure: AssistStructure, withValues: Boolean) {
        val fields: List<FormField>
        val values = HashMap<String, String>()
        var outerDomain: String? = null
        var outerScheme: String? = null

        init {
            val raw = ArrayList<Pair<ViewNode, Triple<String?, String?, String?>>>()
            val boxes = ArrayList<List<Int>>()
            // (x, y): where the parent's content starts on the screen. A node's left/top are relative to its parent, whose own
            // scroll shifts its children; the window's left/top place the root.
            fun walk(node: ViewNode, domain: String?, scheme: String?, x: Int, y: Int) {
                var d = domain
                var s = scheme
                node.webDomain?.let { d = it; s = node.webScheme; if (outerDomain == null) { outerDomain = it; outerScheme = node.webScheme } }
                val left = x + node.left
                val top = y + node.top
                if (node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
                    val html = node.htmlInfo
                    val attrs = html?.attributes
                    val autocomplete = attrs?.firstOrNull { it.first == "autocomplete" }?.second
                    val type = if (html?.tag.equals("input", ignoreCase = true)) attrs?.firstOrNull { it.first == "type" }?.second else null
                    raw.add(node to Triple(Roles.of(node.autofillHints?.toList(), autocomplete, type, node.inputType), d, s))
                    boxes.add(listOf(left, top, left + node.width, top + node.height))
                }
                for (i in 0 until node.childCount) walk(node.getChildAt(i), d, s, left - node.scrollX, top - node.scrollY)
            }
            for (i in 0 until structure.windowNodeCount) {
                val window = structure.getWindowNodeAt(i)
                walk(window.rootViewNode, null, null, window.left, window.top)
            }
            val roles = Roles.withBilling(raw.map { it.second.first })
            fields = raw.indices.mapNotNull { i ->
                val node = raw[i].first
                val role = roles[i] ?: return@mapNotNull null
                val id = node.autofillId!!.toString()
                if (withValues) node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { values[id] = it }
                val viewId = node.idEntry?.let { entry -> node.idPackage?.let { "$it:id/$entry" } ?: entry }
                FormField(id, role, node.isFocused, raw[i].second.second, raw[i].second.third, maxLength(node), node.autofillId,
                          viewId, boxes[i])
            }
        }

        private fun maxLength(node: ViewNode): Int {
            if (node.maxTextLength > 0) return node.maxTextLength
            return node.htmlInfo?.attributes?.firstOrNull { it.first == "maxlength" }?.second?.trim()?.toIntOrNull() ?: -1
        }
    }
}

package dev.hermes.android.voice

import org.json.JSONObject

/**
 * One function result alone, as a release of its own (answered unless it is quiet progress): the wire shape the tests check. The call itself
 * sends releases only (GeminiWire.tools); this lived in GeminiWire until REVIEW-E5 P3-3 found no caller there.
 */
internal fun GeminiWire.tool(id: String, name: String, result: JSONObject): JSONObject =
    tools(listOf(CallReply(id, name, result)), prompt = resultTurn(result) != ResultTurn.Quiet)

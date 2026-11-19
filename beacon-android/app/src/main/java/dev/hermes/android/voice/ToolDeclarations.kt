package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * The profile's own Hermes tools as a v3 call context declares them (calls contract v3, section 1). The plugin names them and
 * writes their schema in a portable JSON Schema subset; the app only checks each one and maps it to the provider. A declaration
 * that does not fit is left out and counted, so one odd tool can never make a provider refuse the whole session; the model can
 * still reach it through discover_tools.
 */
internal class ToolDeclarations private constructor(val tools: List<Tool>, val dropped: Int, val complete: Boolean) {
    /** One declared tool; [parameters] is its schema in the portable subset, checked. */
    internal class Tool(val name: String, val description: String, val parameters: JSONObject, val readOnly: Boolean, val toolId: String)

    /** Gemini FunctionDeclarations: NON_BLOCKING like the call's own functions, schema in Gemini's form ([gemini]). */
    fun gemini(): List<JSONObject> = tools.map {
        JSONObject().put("name", it.name).put("description", it.description).put("behavior", "NON_BLOCKING").put("parameters", gemini(it.parameters))
    }

    companion object {
        /** Contract v3 section 1: the intersection of what Gemini declarations and responses accept, at most 64 characters. */
        val NAME = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
        private const val ONE_MAX = 4096
        private const val ALL_MAX = 49_152
        /** "The maximum number of function declarations that can be provided with the request is 128" (Google, function calling reference). */
        private const val FUNCTIONS_MAX = 128
        private const val DEPTH_MAX = 6
        private const val PROPERTIES_MAX = 64
        private const val ENUM_MAX = 64
        private val TYPES = setOf("object", "string", "number", "integer", "boolean", "array", "null")
        private val TEXTS = setOf("description", "pattern", "format", "title")
        private val COUNTS = setOf("minItems", "maxItems", "minLength", "maxLength")
        private val BOUNDS = setOf("minimum", "maximum")

        /**
         * The declarations a context carries for this call. [reserved] are names the session already declares; a name that
         * repeats one of them, repeats an earlier declaration or takes a reserved prefix is left out. Complete only when the
         * plugin sent every tool and the app kept every one.
         */
        fun from(context: JSONObject, reserved: Set<String>): ToolDeclarations {
            val rows = context.optJSONArray("tool_declarations") ?: return ToolDeclarations(emptyList(), 0, false)
            val kept = ArrayList<Tool>()
            val names = HashSet(reserved)
            var dropped = 0
            var bytes = 2
            for (i in 0 until rows.length()) {
                // The session's own functions count too; a tool past the limit stays reachable through discover_tools.
                if (kept.size >= FUNCTIONS_MAX - reserved.size) { dropped++; continue }
                val tool = tool(rows.opt(i))
                val size = tool?.let { JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters).toString().toByteArray().size }
                if (tool == null || size!! > ONE_MAX || bytes + size + 1 > ALL_MAX || !names.add(tool.name) ||
                    tool.name.startsWith("hermes_") || tool.name.startsWith("phone_")) { dropped++; continue }
                bytes += size + 1
                kept += tool
            }
            return ToolDeclarations(kept, dropped, dropped == 0 && context.optBoolean("tool_declarations_complete"))
        }

        private fun tool(row: Any?): Tool? {
            if (row !is JSONObject) return null
            val name = row.opt("name") as? String ?: return null
            val description = (row.opt("description") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parameters = node(row.opt("parameters"), 1) ?: return null
            if (!name.matches(NAME) || parameters.opt("type") != "object") return null
            return Tool(name, description.take(600), parameters, row.optBoolean("read_only"), row.optString("tool_id"))
        }

        /** One schema node checked against the portable subset, with only its allowed keys; null when it does not fit. */
        private fun node(value: Any?, depth: Int): JSONObject? {
            if (value !is JSONObject || depth > DEPTH_MAX) return null
            val out = JSONObject()
            when (val type = value.opt("type")) {
                is String -> if (type in TYPES) out.put("type", type) else return null
                is JSONArray -> {
                    val parts = (0 until type.length()).map { type.opt(it) }
                    if (parts.size != 2 || "null" !in parts || parts.any { it !is String || it !in TYPES } || parts[0] == parts[1]) return null
                    out.put("type", JSONArray().put(parts.first { it != "null" }).put("null"))
                }
                null -> if (!value.has("anyOf")) return null
                else -> return null
            }
            for (key in value.keys()) {
                val child = value.opt(key)
                when (key) {
                    "type" -> Unit
                    in TEXTS -> out.put(key, child as? String ?: return null)
                    in COUNTS -> out.put(key, (child as? Number)?.toDouble()?.takeIf { it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toLong() ?: return null)
                    in BOUNDS -> out.put(key, (child as? Number)?.takeIf { it.toDouble().isFinite() } ?: return null)
                    "default" -> out.put(key, child)
                    "enum" -> {
                        val type = value.opt("type")
                        val text = type == "string" || type is JSONArray && (0 until type.length()).any { type.opt(it) == "string" }
                        if (child !is JSONArray || child.length() !in 1..ENUM_MAX || !text) return null
                        out.put(key, JSONArray().apply { for (j in 0 until child.length()) put((child.opt(j) as? String)?.takeIf { it.length <= 100 } ?: return null) })
                    }
                    "required" -> {
                        if (child !is JSONArray) return null
                        out.put(key, JSONArray().apply { for (j in 0 until child.length()) put(child.opt(j) as? String ?: return null) })
                    }
                    "properties" -> {
                        if (child !is JSONObject || child.length() > PROPERTIES_MAX) return null
                        out.put(key, JSONObject().apply { for (name in child.keys()) put(name, node(child.opt(name), depth + 1) ?: return null) })
                    }
                    "items" -> out.put(key, node(child, depth + 1) ?: return null)
                    "anyOf" -> {
                        if (child !is JSONArray || child.length() == 0) return null
                        out.put(key, JSONArray().apply { for (j in 0 until child.length()) put(node(child.opt(j), depth + 1) ?: return null) })
                    }
                    // Anything else is outside the portable subset; the plugin still validates arguments against the full schema.
                    else -> Unit
                }
            }
            // Gemini Live refuses the whole session for an array without items (probe, 14 Nov 2026: close 1007 "...items: missing field").
            val array = out.opt("type").let { it == "array" || it is JSONArray && (0 until it.length()).any { i -> it.opt(i) == "array" } }
            if (array && !out.has("items")) return null
            return out
        }

        /**
         * A portable schema in Gemini's form (OpenAPI subset): types uppercased, `[T, "null"]` as `T` plus `nullable`, every other key
         * as it is. Gemini's int64 counts (minItems, maxLength, ...) stay JSON numbers, which proto JSON accepts.
         */
        fun gemini(schema: JSONObject): JSONObject = walk(schema) { node, out ->
            when (val type = node.opt("type")) {
                is String -> out.put("type", type.uppercase())
                is JSONArray -> {
                    out.put("type", (0 until type.length()).map { type.getString(it) }.first { it != "null" }.uppercase())
                    out.put("nullable", true)
                }
            }
        }

        /**
         * A schema in Gemini's form back in JSON Schema, for providers that take JSON Schema (Grok, OpenAI): the exact inverse of
         * [gemini], so a declared tool reaches them with its portable schema unchanged.
         */
        fun portable(schema: JSONObject): JSONObject = walk(schema, skip = setOf("nullable")) { node, out ->
            val type = (node.opt("type") as? String)?.lowercase()
            if (type != null) out.put("type", if (node.optBoolean("nullable")) JSONArray().put(type).put("null") else type)
        }

        /** Copies a schema node by node; [type] writes each node's type, the structure keys are walked, the rest is copied. */
        private fun walk(node: JSONObject, skip: Set<String> = emptySet(), type: (JSONObject, JSONObject) -> Unit): JSONObject {
            val out = JSONObject()
            type(node, out)
            for (key in node.keys()) {
                val child = node.get(key)
                when {
                    key == "type" || key in skip -> Unit
                    key == "properties" && child is JSONObject -> out.put(key, JSONObject().apply { for (name in child.keys()) put(name, walk(child.getJSONObject(name), skip, type)) })
                    key == "items" && child is JSONObject -> out.put(key, walk(child, skip, type))
                    key == "anyOf" && child is JSONArray -> out.put(key, JSONArray().apply { for (j in 0 until child.length()) put(walk(child.getJSONObject(j), skip, type)) })
                    else -> out.put(key, child)
                }
            }
            return out
        }
    }
}

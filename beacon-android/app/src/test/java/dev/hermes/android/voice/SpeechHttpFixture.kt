package dev.hermes.android.voice

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

internal class SpeechHttpFixture : Call.Factory {
    inner class Pending(private val value: Request) : Call {
        lateinit var callback: Callback
        private var executed = false
        @Volatile var canceled = false
            private set
        override fun request() = value
        override fun execute(): Response = error("Fixture uses asynchronous calls")
        override fun enqueue(responseCallback: Callback) {
            check(!executed); executed = true; callback = responseCallback; requests += this
        }
        override fun cancel() {
            if (!canceled) { canceled = true; if (::callback.isInitialized) callback.onFailure(this, IOException("Synthetic canceled speech call")) }
        }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = Pending(value)
        fun body(): JSONObject {
            val buffer = okio.Buffer(); requireNotNull(value.body).writeTo(buffer)
            return JSONObject(buffer.readUtf8())
        }
        fun respond(value: JSONObject, status: Int = 200) = respondBody(value.toString().toResponseBody("application/json".toMediaType()), status)
        fun respondBody(body: ResponseBody, status: Int = 200) = callback.onResponse(this,
            Response.Builder().request(this.value).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body).build())
        fun fail(error: IOException) = callback.onFailure(this, error)
    }
    val requests = CopyOnWriteArrayList<Pending>()
    override fun newCall(request: Request): Call = Pending(request)
    fun text(index: Int = requests.lastIndex): String = requests[index].body().getJSONArray("contents")
        .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
    fun respond(value: JSONObject = pcm(), status: Int = 200, index: Int = requests.lastIndex) = requests[index].respond(value, status)
    fun fail(error: IOException, index: Int = requests.lastIndex) = requests[index].fail(error)

    companion object {
        fun pcm(data: String = "AAE=", mime: String = "audio/l16; rate=24000; channels=1", finish: String = "STOP") = JSONObject()
            .put("candidates", JSONArray().put(JSONObject().put("finishReason", finish).put("content", JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)))))))
    }
}

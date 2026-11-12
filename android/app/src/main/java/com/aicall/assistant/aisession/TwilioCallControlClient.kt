package com.aicall.assistant.aisession

import com.aicall.assistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

data class TwilioActiveCall(
    val callSid: String,
    val phoneNumber: String,
    val canTakeOver: Boolean
)

object TwilioCallControlClient {
    private val client = OkHttpClient()

    private fun request(url: String, method: String = "GET"): Request {
        val baseUrl = BuildConfig.TWILIO_CONTROL_BASE_URL.trimEnd('/')
        require(baseUrl.startsWith("https://")) {
            "Set BACKEND_HTTP_URL to your public HTTPS backend URL in Gradle properties."
        }
        require(BuildConfig.BACKEND_AUTH_TOKEN.isNotBlank()) {
            "Set BACKEND_AUTH_TOKEN in Gradle properties to enable AI call controls."
        }
        return Request.Builder()
            .url("$baseUrl$url")
            .header("Authorization", "Bearer ${BuildConfig.BACKEND_AUTH_TOKEN}")
            .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)
            .build()
    }

    suspend fun activeCalls(): List<TwilioActiveCall> = withContext(Dispatchers.IO) {
        val httpRequest = request("/twilio/active-calls")
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Backend returned HTTP ${response.code}")
            val json = JSONObject(response.body?.string().orEmpty())
            val results = json.optJSONArray("calls") ?: return@withContext emptyList()
            List(results.length()) { index ->
                val item = results.getJSONObject(index)
                TwilioActiveCall(
                    callSid = item.getString("call_sid"),
                    phoneNumber = item.optString("phone_number", "Unknown caller"),
                    canTakeOver = item.optBoolean("can_take_over", false)
                )
            }
        }
    }

    suspend fun requestTakeover(callSid: String) = withContext(Dispatchers.IO) {
        val httpRequest = request("/twilio/calls/${java.net.URLEncoder.encode(callSid, "UTF-8")}/takeover", "POST")
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Backend returned HTTP ${response.code}")
        }
    }
}

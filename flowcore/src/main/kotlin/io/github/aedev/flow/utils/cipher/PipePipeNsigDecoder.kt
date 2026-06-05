package io.github.aedev.flow.utils.cipher

import android.util.Log
import io.github.aedev.flow.network.AppProxyManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PipePipeNsigDecoder {
    private const val TAG = "PipePipeNsig"
    private const val LATEST_PLAYER_URL = "https://api.pipepipe.dev/decoder/latest-player"
    private const val DECODE_URL = "https://api.pipepipe.dev/decoder/decode"
    private const val USER_AGENT = "PipePipe/4.9.0"
    private const val PLAYER_TTL_MS = 24L * 60L * 60L * 1000L

    private val nParamRegex = Regex("([?&])n=([^&]+)")
    private val nCache = ConcurrentHashMap<String, String>()

    @Volatile private var playerId: String? = null
    @Volatile private var playerIdExpiryMs = 0L

    private val httpClient: OkHttpClient
        get() = AppProxyManager.applyTo(OkHttpClient.Builder())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    @Synchronized
    private fun ensurePlayerId(): String? {
        val now = System.currentTimeMillis()
        playerId?.let { if (now < playerIdExpiryMs) return it }
        return try {
            val req = Request.Builder()
                .url(LATEST_PLAYER_URL)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "latest-player HTTP ${resp.code}")
                    return null
                }
                val id = JSONObject(resp.body?.string().orEmpty())
                    .optString("player")
                    .takeIf { it.isNotEmpty() }
                    ?: return null
                playerId = id
                playerIdExpiryMs = now + PLAYER_TTL_MS
                Log.d(TAG, "latest-player ok: id=$id")
                id
            }
        } catch (e: Exception) {
            Log.w(TAG, "latest-player fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun rawN(url: String): String? =
        nParamRegex.find(url)?.groupValues?.get(2)?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (e: Exception) {
                it
            }
        }

    private fun decodeN(n: String): String? {
        val pid = ensurePlayerId() ?: return null
        nCache["$pid:$n"]?.let { return it }
        return try {
            val req = Request.Builder()
                .url("$DECODE_URL?player=$pid&n=${URLEncoder.encode(n, "UTF-8")}")
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val data = parseData(resp.body?.string()) ?: return null
                val decoded = data.optString(n).takeIf { it.isNotEmpty() } ?: return null
                nCache["$pid:$n"] = decoded
                decoded
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseData(body: String?): JSONObject? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body)
                .getJSONArray("responses")
                .getJSONObject(0)
                .getJSONObject("data")
        } catch (e: Exception) {
            Log.w(TAG, "unexpected response shape: ${e.message}")
            null
        }
    }

    fun deobfuscateUrl(url: String): String? {
        val n = rawN(url) ?: return null
        val decoded = decodeN(n) ?: return null
        if (decoded == n) return null
        return url.replaceFirst(nParamRegex, "\$1n=${URLEncoder.encode(decoded, "UTF-8")}")
    }
}

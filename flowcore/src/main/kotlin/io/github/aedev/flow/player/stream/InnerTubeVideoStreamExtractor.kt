package io.github.aedev.flow.player.stream

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.pages.NewPipeExtractor
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import io.github.aedev.flow.player.sabr.integration.SabrUrlResolver
import io.github.aedev.flow.utils.cipher.CipherDeobfuscator
import io.github.aedev.flow.utils.potoken.PoTokenGenerator
import io.github.aedev.flow.utils.potoken.PoTokenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
object InnerTubeVideoStreamExtractor {
    private const val TAG = "InnerTubeVideoExtractor"
    private const val PER_CLIENT_TIMEOUT_MS = 6000L
    private val poTokenGenerator = PoTokenGenerator()
    private var cachedSignatureTimestamp: Int? = null
    private val validationClient: OkHttpClient by lazy {
        AppProxyManager.applyTo(OkHttpClient.Builder())
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val VIDEO_STREAM_CLIENTS: List<YouTubeClient> = listOf(
        YouTubeClient.WEB_REMIX,
        YouTubeClient.WEB,
        YouTubeClient.ANDROID,
        YouTubeClient.MOBILE,
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.ANDROID_VR_NO_AUTH,
        YouTubeClient.IPADOS,
        YouTubeClient.IOS,
        YouTubeClient.ANDROID_CREATOR,
    )

    data class VideoExtractionResult(
        val videoFormats: List<PlayerResponse.StreamingData.Format>,
        val audioFormats: List<PlayerResponse.StreamingData.Format>,
        val playerResponse: PlayerResponse,
        val usedClient: YouTubeClient,
        val sabrInfo: SabrStreamInfo?,
    )

    suspend fun extract(videoId: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting extraction for $videoId with ${VIDEO_STREAM_CLIENTS.size} clients")

        val failureReasons = mutableListOf<String>()
        var poToken: PoTokenResult? = null
        val sessionId = YouTube.dataSyncId ?: YouTube.visitorData

        fun getPoToken(): PoTokenResult? {
            poToken?.let { return it }
            if (sessionId.isNullOrBlank()) return null
            return try {
                poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    ?.also { poToken = it }
            } catch (e: Exception) {
                val reason = "poToken generation failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, reason)
                PlayerDiagnostics.logWarning(TAG, reason)
                null
            }
        }

        for ((index, client) in VIDEO_STREAM_CLIENTS.withIndex()) {
            if (client.loginRequired && YouTube.cookie.isNullOrBlank()) {
                failureReasons.add("${client.clientName}: login required")
                continue
            }

            try {
                Log.d(TAG, "Trying client ${index + 1}/${VIDEO_STREAM_CLIENTS.size}: ${client.clientName} v${client.clientVersion}")

                val playerResponse = withTimeoutOrNull(PER_CLIENT_TIMEOUT_MS) {
                    val clientPoToken = if (client.useWebPoTokens) getPoToken()?.playerRequestPoToken else null
                    val signatureTimestamp = if (client.useSignatureTimestamp) getSignatureTimestamp(videoId) else null
                    YouTube.player(
                        videoId = videoId,
                        client = client,
                        signatureTimestamp = signatureTimestamp,
                        poToken = clientPoToken
                    ).getOrNull()
                }

                if (playerResponse == null) {
                    val reason = "${client.clientName}: timeout or null response"
                    Log.w(TAG, reason)
                    failureReasons.add(reason)
                    continue
                }

                if (playerResponse.playabilityStatus.status != "OK") {
                    val reason = "${client.clientName}: status=${playerResponse.playabilityStatus.status}, reason=${playerResponse.playabilityStatus.reason}"
                    Log.w(TAG, reason)
                    failureReasons.add(reason)
                    continue
                }

                val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats
                if (adaptiveFormats.isNullOrEmpty()) {
                    val reason = "${client.clientName}: no adaptive formats in response"
                    Log.w(TAG, reason)
                    failureReasons.add(reason)
                    continue
                }

                val formatsWithUrl = adaptiveFormats
                    .filter { !it.url.isNullOrEmpty() }
                    .map { it.withPlayableUrl(videoId, if (client.useWebPoTokens) poToken?.streamingDataPoToken else null) }
                if (formatsWithUrl.isEmpty()) {
                    val sabrInfo = try {
                        SabrUrlResolver.resolve(playerResponse)
                    } catch (e: Exception) {
                        Log.d(TAG, "SABR resolution failed: ${e.message}")
                        null
                    }

                    if (sabrInfo != null) {
                        Log.i(TAG, "Success with ${client.clientName}: SABR-only response, audioItag=${sabrInfo.audioItag}, videoItag=${sabrInfo.videoItag}")
                        return@withContext VideoExtractionResult(
                            videoFormats = emptyList(),
                            audioFormats = emptyList(),
                            playerResponse = playerResponse,
                            usedClient = client,
                            sabrInfo = sabrInfo,
                        )
                    } else {
                        val reason = "${client.clientName}: ${adaptiveFormats.size} adaptive formats but none have direct URLs or usable SABR"
                        Log.w(TAG, reason)
                        failureReasons.add(reason)
                        continue
                    }
                }

                val videoFormats = formatsWithUrl.filter { !it.isAudio && it.height != null && it.width != null }
                val audioFormats = formatsWithUrl.filter { it.isAudio }
                val sabrInfo = try {
                    SabrUrlResolver.resolve(playerResponse)
                } catch (e: Exception) {
                    Log.d(TAG, "SABR resolution failed: ${e.message}")
                    null
                }

                if (videoFormats.isEmpty()) {
                    if (sabrInfo != null) {
                        Log.i(TAG, "Success with ${client.clientName}: SABR fallback for response without direct video URLs")
                        return@withContext VideoExtractionResult(
                            videoFormats = emptyList(),
                            audioFormats = audioFormats,
                            playerResponse = playerResponse,
                            usedClient = client,
                            sabrInfo = sabrInfo,
                        )
                    } else {
                        val reason = "${client.clientName}: no video formats with direct URLs (${formatsWithUrl.size} total formats)"
                        Log.w(TAG, reason)
                        failureReasons.add(reason)
                        continue
                    }
                }
                if (audioFormats.isEmpty()) {
                    if (sabrInfo != null) {
                        Log.i(TAG, "Success with ${client.clientName}: SABR fallback for response without direct audio URLs")
                        return@withContext VideoExtractionResult(
                            videoFormats = videoFormats,
                            audioFormats = emptyList(),
                            playerResponse = playerResponse,
                            usedClient = client,
                            sabrInfo = sabrInfo,
                        )
                    } else {
                        val reason = "${client.clientName}: no audio formats with direct URLs"
                        Log.w(TAG, reason)
                        failureReasons.add(reason)
                        continue
                    }
                }

                val playableVideoFormats = validatePlayableFormats(videoFormats, client, videoId, "video")
                val playableAudioFormats = validatePlayableFormats(audioFormats, client, videoId, "audio")
                if (playableVideoFormats.isEmpty() || playableAudioFormats.isEmpty()) {
                    val reason = "${client.clientName}: direct URLs failed validation (video=${playableVideoFormats.size}/${videoFormats.size}, audio=${playableAudioFormats.size}/${audioFormats.size})"
                    Log.w(TAG, reason)
                    PlayerDiagnostics.logWarning(TAG, reason)
                    failureReasons.add(reason)
                    if (sabrInfo != null) {
                        return@withContext VideoExtractionResult(
                            videoFormats = emptyList(),
                            audioFormats = emptyList(),
                            playerResponse = playerResponse,
                            usedClient = client,
                            sabrInfo = sabrInfo,
                        )
                    }
                    continue
                }

                val heights = playableVideoFormats.mapNotNull { it.height }.distinct().sorted()
                Log.i(TAG, "Success with ${client.clientName}: ${playableVideoFormats.size} video (${heights.joinToString()}p), ${playableAudioFormats.size} audio, using validated direct URLs, sabrFallbackAvailable=${sabrInfo != null}")
                PlayerDiagnostics.logInfo(TAG, "InnerTube streams via ${client.clientName}: ${playableVideoFormats.size} video, ${playableAudioFormats.size} audio, validated=true, sabr=${sabrInfo != null}, pot=${poToken != null}")

                return@withContext VideoExtractionResult(
                    videoFormats = playableVideoFormats,
                    audioFormats = playableAudioFormats,
                    playerResponse = playerResponse,
                    usedClient = client,
                    sabrInfo = sabrInfo,
                )
            } catch (e: Exception) {
                val reason = "${client.clientName}: exception=${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, reason)
                failureReasons.add(reason)
            }
        }

        Log.e(TAG, "All ${VIDEO_STREAM_CLIENTS.size} clients failed for $videoId. Reasons: ${failureReasons.joinToString(" | ")}")
        PlayerDiagnostics.logWarning(TAG, "InnerTube extraction failed for $videoId: ${failureReasons.joinToString(" | ").take(500)}")
        null
    }

    private fun validatePlayableFormats(
        formats: List<PlayerResponse.StreamingData.Format>,
        client: YouTubeClient,
        videoId: String,
        kind: String
    ): List<PlayerResponse.StreamingData.Format> {
        return formats.filter { format ->
            val playable = format.url?.let { checkStreamUrl(it, client) } == true
            if (!playable) {
                Log.w(TAG, "Rejected ${client.clientName} $kind itag=${format.itag} for $videoId: URL validation failed")
            }
            playable
        }
    }

    private fun checkStreamUrl(url: String, client: YouTubeClient): Boolean {
        return try {
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("User-Agent", client.userAgent)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            YouTube.cookie?.let { requestBuilder.header("Cookie", it) }
            validationClient.newCall(requestBuilder.build()).execute().use { response ->
                response.code in 200..299
            }
        } catch (e: Exception) {
            Log.d(TAG, "Stream URL validation exception: ${e.message}")
            false
        }
    }

    private suspend fun PlayerResponse.StreamingData.Format.withPlayableUrl(
        videoId: String,
        streamingPoToken: String?
    ): PlayerResponse.StreamingData.Format {
        var rawUrl = url ?: return this

        if (rawUrl.contains("n=")) {
            try {
                val transformedUrl = CipherDeobfuscator.transformNParamInUrl(rawUrl)
                if (transformedUrl != rawUrl) {
                    Log.d(TAG, "Applied n-transform for $videoId itag=$itag")
                }
                rawUrl = transformedUrl
            } catch (e: Exception) {
                Log.w(TAG, "n-transform failed for $videoId itag=$itag: ${e.message}")
            }
        }

        val finalUrl = try {
            if (!streamingPoToken.isNullOrBlank() && Uri.parse(rawUrl).getQueryParameter("pot").isNullOrBlank()) {
                val separator = if ("?" in rawUrl) "&" else "?"
                "$rawUrl${separator}pot=${Uri.encode(streamingPoToken)}"
            } else {
                rawUrl
            }
        } catch (e: Exception) {
            rawUrl
        }

        return copy(url = finalUrl)
    }

    private fun getSignatureTimestamp(videoId: String): Int? {
        cachedSignatureTimestamp?.let { return it }
        return try {
            CipherDeobfuscator.getSignatureTimestamp()
                ?: NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Signature timestamp lookup failed: ${e.message}")
            null
        }?.also { cachedSignatureTimestamp = it }
    }
}

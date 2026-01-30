/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.metrolist.music.constants.AudioQuality
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /**
     * Main client for playback - ANDROID_VR provides direct URLs without JS signature decryption.
     */
    private val MAIN_CLIENT = ANDROID_VR_NO_AUTH

    /**
     * Fallback clients ordered by reliability.
     * TVHTML5 handles uploaded content, TVHTML5_SIMPLY_EMBEDDED_PLAYER handles age-restricted content.
     */
    private val FALLBACK_CLIENTS = arrayOf(
        WEB_REMIX,
        TVHTML5,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    /**
     * Playability status constants for better handling of different video states
     */
    private object PlayabilityStatus {
        const val OK = "OK"
        const val UNPLAYABLE = "UNPLAYABLE"
        const val ERROR = "ERROR"
        const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
        const val AGE_CHECK_REQUIRED = "AGE_CHECK_REQUIRED"
        const val AGE_VERIFICATION_REQUIRED = "AGE_VERIFICATION_REQUIRED"
        const val CONTENT_CHECK_REQUIRED = "CONTENT_CHECK_REQUIRED"
        const val LIVE_STREAM_OFFLINE = "LIVE_STREAM_OFFLINE"
    }

    /**
     * Check if the playability status indicates content that might be playable with fallback clients
     */
    private fun isPlayableOrRetryable(status: String?): Boolean {
        return when (status) {
            PlayabilityStatus.OK -> true
            // These statuses might work with different clients
            PlayabilityStatus.UNPLAYABLE,
            PlayabilityStatus.LOGIN_REQUIRED,
            PlayabilityStatus.AGE_CHECK_REQUIRED,
            PlayabilityStatus.AGE_VERIFICATION_REQUIRED,
            PlayabilityStatus.CONTENT_CHECK_REQUIRED -> true
            else -> false
        }
    }

    /**
     * Check if we should try NewPipe extraction for this status
     */
    private fun shouldTryNewPipeExtraction(status: String?): Boolean {
        return when (status) {
            PlayabilityStatus.UNPLAYABLE,
            PlayabilityStatus.LOGIN_REQUIRED,
            PlayabilityStatus.AGE_CHECK_REQUIRED,
            PlayabilityStatus.AGE_VERIFICATION_REQUIRED,
            PlayabilityStatus.CONTENT_CHECK_REQUIRED -> true
            else -> false
        }
    }

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from the main client.
     * Format & stream can be from main client or fallback clients.
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId, client: ${MAIN_CLIENT.clientName}")

        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: $signatureTimestamp")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        Timber.tag(logTag).d("Attempting to get player response using main client: ${MAIN_CLIENT.clientName}")
        
        // Use getOrNull() instead of getOrThrow() to allow fallback clients to be tried
        val mainPlayerResponse =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp).getOrNull()
        
        // Try to get audioConfig and videoDetails from main client or fallback to WEB_REMIX
        val (audioConfig, videoDetails) = if (mainPlayerResponse?.playabilityStatus?.status == PlayabilityStatus.OK) {
            mainPlayerResponse.playerConfig?.audioConfig to mainPlayerResponse.videoDetails
        } else {
            Timber.tag(logTag).d("Main client failed or not OK, trying WEB_REMIX for metadata")
            val webRemixResponse = YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp).getOrNull()
            webRemixResponse?.playerConfig?.audioConfig to webRemixResponse?.videoDetails
        }

        // Always use WEB_REMIX for playbackTracking to ensure history sync works
        // ANDROID_VR clients don't support login and may not return valid playbackTracking
        val playbackTracking = run {
            Timber.tag(logTag).d("Fetching playbackTracking from WEB_REMIX for history sync")
            YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp)
                .getOrNull()?.playbackTracking ?: mainPlayerResponse?.playbackTracking
        }

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var lastStatus: String? = null
        var lastReason: String? = null

        for (clientIndex in (-1 until FALLBACK_CLIENTS.size)) {
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client: YouTubeClient
            if (clientIndex == -1) {
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from main client: ${client.clientName}")
            } else {
                client = FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()
            }

            val currentStatus = streamPlayerResponse?.playabilityStatus?.status
            val currentReason = streamPlayerResponse?.playabilityStatus?.reason
            lastStatus = currentStatus
            lastReason = currentReason

            // Check if playable or might be playable with different approach
            if (currentStatus == PlayabilityStatus.OK) {
                Timber.tag(logTag).d("Player response status OK for client: ${client.clientName}")

                format = findFormat(streamPlayerResponse!!, audioQuality, connectivityManager)

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${client.clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Skip validation for main client for faster playback
                if (clientIndex == -1) {
                    Timber.tag(logTag).d("Using main client directly without validation for faster playback")
                    break
                }

                if (clientIndex == FALLBACK_CLIENTS.size - 1) {
                    Timber.tag(logTag).d("Using last fallback client without validation: ${client.clientName}")
                    break
                }

                if (validateStatus(streamUrl)) {
                    Timber.tag(logTag).d("Stream validated successfully with client: ${client.clientName}")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${client.clientName}")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: $currentStatus, reason: $currentReason")
                
                // For age-restricted or uploaded content, try NewPipe extraction immediately
                if (shouldTryNewPipeExtraction(currentStatus) && streamPlayerResponse != null) {
                    Timber.tag(logTag).d("Attempting NewPipe extraction for restricted content (status: $currentStatus)")
                    
                    val newPipeResult = tryNewPipeExtraction(videoId, streamPlayerResponse, audioQuality, connectivityManager)
                    if (newPipeResult != null) {
                        format = newPipeResult.first
                        streamUrl = newPipeResult.second
                        streamExpiresInSeconds = 21600 // 6 hours default for NewPipe streams
                        Timber.tag(logTag).d("NewPipe extraction successful for client: ${client.clientName}")
                        break
                    }
                }
            }
        }

        // If all regular clients failed, try full NewPipe extraction as last resort
        if (streamUrl == null && format == null) {
            Timber.tag(logTag).d("All clients failed, attempting full NewPipe extraction as last resort")
            val lastResponse = streamPlayerResponse ?: mainPlayerResponse
            if (lastResponse != null) {
                val newPipeResult = tryNewPipeExtraction(videoId, lastResponse, audioQuality, connectivityManager)
                if (newPipeResult != null) {
                    format = newPipeResult.first
                    streamUrl = newPipeResult.second
                    streamExpiresInSeconds = 21600 // 6 hours default
                    streamPlayerResponse = lastResponse
                }
            }
        }

        if (streamPlayerResponse == null && mainPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        // Use last known response for error message
        val finalResponse = streamPlayerResponse ?: mainPlayerResponse!!

        if (streamUrl == null || format == null) {
            val errorReason = lastReason ?: finalResponse.playabilityStatus.reason ?: "Unknown error"
            val errorStatus = lastStatus ?: finalResponse.playabilityStatus.status
            Timber.tag(logTag).e("Playability status not OK: $errorStatus - $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }

    /**
     * Try to extract streams using NewPipe for age-restricted or uploaded content
     */
    private fun tryNewPipeExtraction(
        videoId: String,
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager
    ): Pair<PlayerResponse.StreamingData.Format, String>? {
        return try {
            Timber.tag(logTag).d("Attempting NewPipe full extraction for videoId: $videoId")
            
            val streamsResult = NewPipeUtils.getStreamsFromNewPipe(videoId)
            if (streamsResult.isFailure) {
                Timber.tag(logTag).w("NewPipe extraction failed: ${streamsResult.exceptionOrNull()?.message}")
                return null
            }
            
            val streams = streamsResult.getOrNull() ?: return null
            if (streams.isEmpty()) {
                Timber.tag(logTag).w("NewPipe returned no streams")
                return null
            }
            
            Timber.tag(logTag).d("NewPipe found ${streams.size} streams")
            
            // Try to find matching format from player response
            val format = findFormat(playerResponse, audioQuality, connectivityManager)
            if (format != null && streams.containsKey(format.itag)) {
                val url = streams[format.itag]!!
                Timber.tag(logTag).d("Found matching stream for itag ${format.itag}")
                return format to url
            }
            
            // If no matching format, try to get best audio from NewPipe
            val bestAudioResult = NewPipeUtils.getBestAudioStreamUrl(videoId)
            if (bestAudioResult.isSuccess) {
                val url = bestAudioResult.getOrNull()
                if (url != null) {
                    // Find any audio format to use as template
                    val audioFormat = playerResponse.streamingData?.adaptiveFormats
                        ?.filter { it.isAudio }
                        ?.firstOrNull()
                    
                    if (audioFormat != null) {
                        Timber.tag(logTag).d("Using best audio stream from NewPipe")
                        return audioFormat to url
                    }
                }
            }
            
            // Last resort: use first available stream
            val firstStream = streams.entries.firstOrNull()
            if (firstStream != null) {
                val audioFormat = playerResponse.streamingData?.adaptiveFormats
                    ?.filter { it.isAudio }
                    ?.firstOrNull()
                if (audioFormat != null) {
                    Timber.tag(logTag).d("Using first available stream from NewPipe as fallback")
                    return audioFormat to firstStream.value
                }
            }
            
            null
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "NewPipe extraction failed with exception")
            reportException(e)
            null
        }
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using WEB_REMIX client")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }

    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).d("Signature timestamp obtained: $it") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to get signature timestamp") }
            .getOrNull()
    }

    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, itag: ${format.itag}, videoId: $videoId")
        
        // First try: Use direct URL if available
        format.url?.let { url ->
            Timber.tag(logTag).d("Using direct URL from format")
            return url
        }
        
        // Second try: Use cipher decryption
        val cipherResult = NewPipeUtils.getStreamUrl(format, videoId)
        if (cipherResult.isSuccess) {
            Timber.tag(logTag).d("Stream URL obtained via cipher decryption")
            return cipherResult.getOrNull()
        }
        
        Timber.tag(logTag).w("Cipher decryption failed, trying full NewPipe extraction...")
        
        // Third try: Use full NewPipe extraction (handles age-restricted content)
        val streamsResult = NewPipeUtils.getStreamsFromNewPipe(videoId)
        if (streamsResult.isSuccess) {
            val streams = streamsResult.getOrNull() ?: emptyMap()
            val url = streams[format.itag]
            if (url != null) {
                Timber.tag(logTag).d("Stream URL obtained via NewPipe extraction for itag: ${format.itag}")
                return url
            }
            // If exact itag not found, try to find any audio stream
            if (format.isAudio) {
                val audioUrl = streams.entries.firstOrNull()?.value
                if (audioUrl != null) {
                    Timber.tag(logTag).d("Using alternative audio stream from NewPipe")
                    return audioUrl
                }
            }
        }
        
        Timber.tag(logTag).e("All methods failed to get stream URL for videoId: $videoId")
        cipherResult.onFailure { reportException(it) }
        streamsResult.onFailure { reportException(it) }
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}

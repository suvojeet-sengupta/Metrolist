/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * 
 * Player utilities based on SimpMusic's approach for handling
 * various content types including age-restricted and uploaded songs.
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.metrolist.music.constants.AudioQuality
import com.metrolist.innertube.NewPipeUtils
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * YouTube Player Utilities - SimpMusic style implementation
 * 
 * This implementation follows SimpMusic's approach:
 * 1. Try multiple clients (WEB_REMIX, TVHTML5)
 * 2. Use NewPipe for URL decryption
 * 3. Validate URLs to avoid 403 errors
 */
object YTPlayerUtils {
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /**
     * Clients to try for playback - following SimpMusic's approach
     */
    private val PLAYER_CLIENTS = listOf(WEB_REMIX, TVHTML5)

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    /**
     * Get player response for playback - SimpMusic style implementation
     * 
     * Flow:
     * 1. Get signature timestamp from NewPipe
     * 2. Try each client to get player response
     * 3. Use NewPipe to decode stream URLs
     * 4. Validate URLs and return first working one
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("Starting playback for videoId: $videoId")

        val signatureTimestamp = NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(TAG).d("Signature timestamp: $it") }
            .onFailure { Timber.tag(TAG).w("Failed to get signature timestamp: ${it.message}") }
            .getOrNull()

        var decodedPlayerResponse: PlayerResponse? = null
        var workingClient: YouTubeClient? = null

        // Try each client - similar to SimpMusic's approach
        for (client in PLAYER_CLIENTS) {
            Timber.tag(TAG).d("Trying client: ${client.clientName}")

            val playerResponse = YouTube.player(videoId, playlistId, client, signatureTimestamp)
                .getOrNull()

            if (playerResponse == null) {
                Timber.tag(TAG).d("Failed to get player response from ${client.clientName}")
                continue
            }

            Timber.tag(TAG).d("Got response from ${client.clientName}, status: ${playerResponse.playabilityStatus.status}")

            // Try NewPipe extraction regardless of status - it might handle restricted content
            val newPipeResponse = NewPipeUtils.newPipePlayer(videoId, playerResponse)
            
            if (newPipeResponse != null) {
                // Validate URLs
                val testUrl = newPipeResponse.streamingData?.adaptiveFormats
                    ?.firstOrNull { it.isAudio }?.url
                    ?: newPipeResponse.streamingData?.formats?.firstOrNull()?.url

                if (testUrl != null && NewPipeUtils.validateUrl(testUrl)) {
                    decodedPlayerResponse = newPipeResponse
                    workingClient = client
                    Timber.tag(TAG).d("Found working stream with client: ${client.clientName}")
                    break
                } else {
                    Timber.tag(TAG).d("URL validation failed for client: ${client.clientName}")
                }
            } else {
                Timber.tag(TAG).d("NewPipe extraction returned null for client: ${client.clientName}")
            }
        }

        // If all clients with NewPipe failed, try direct NewPipe extraction
        if (decodedPlayerResponse == null) {
            Timber.tag(TAG).d("All clients failed, trying direct NewPipe extraction")
            
            // Get a basic player response for metadata
            val metadataResponse = YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp)
                .getOrNull()

            val streamsResult = NewPipeUtils.getStreamsFromNewPipe(videoId)
            if (streamsResult.isSuccess) {
                val streams = streamsResult.getOrNull() ?: emptyMap()
                if (streams.isNotEmpty()) {
                    // Find best audio stream
                    val audioItags = listOf(251, 250, 249, 140, 139) // Opus then AAC
                    var bestUrl: String? = null
                    var bestItag: Int? = null
                    
                    for (itag in audioItags) {
                        if (streams.containsKey(itag)) {
                            val url = streams[itag]!!
                            if (NewPipeUtils.validateUrl(url)) {
                                bestUrl = url
                                bestItag = itag
                                break
                            }
                        }
                    }

                    // If no preferred itag found, try any stream
                    if (bestUrl == null) {
                        for ((itag, url) in streams) {
                            if (NewPipeUtils.validateUrl(url)) {
                                bestUrl = url
                                bestItag = itag
                                break
                            }
                        }
                    }

                    if (bestUrl != null && bestItag != null) {
                        Timber.tag(TAG).d("Found working stream via direct NewPipe, itag: $bestItag")
                        
                        // Create synthetic format
                        val format = createSyntheticFormat(bestItag, bestUrl)
                        
                        return@runCatching PlaybackData(
                            audioConfig = metadataResponse?.playerConfig?.audioConfig,
                            videoDetails = metadataResponse?.videoDetails,
                            playbackTracking = metadataResponse?.playbackTracking,
                            format = format,
                            streamUrl = bestUrl,
                            streamExpiresInSeconds = 21600, // 6 hours
                        )
                    }
                }
            }
        }

        if (decodedPlayerResponse == null) {
            Timber.tag(TAG).e("All extraction methods failed for videoId: $videoId")
            throw PlaybackException(
                "Failed to get playable stream",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        // Find best audio format
        val format = findBestAudioFormat(decodedPlayerResponse, audioQuality, connectivityManager)
            ?: throw PlaybackException(
                "No suitable audio format found",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )

        val streamUrl = format.url
            ?: throw PlaybackException(
                "Stream URL is null",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )

        val expiresInSeconds = decodedPlayerResponse.streamingData?.expiresInSeconds ?: 21600

        Timber.tag(TAG).d("Successfully got playback data: format=${format.mimeType}, bitrate=${format.bitrate}")

        PlaybackData(
            audioConfig = decodedPlayerResponse.playerConfig?.audioConfig,
            videoDetails = decodedPlayerResponse.videoDetails,
            playbackTracking = decodedPlayerResponse.playbackTracking,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = expiresInSeconds,
        )
    }

    /**
     * Simple player response for metadata only
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(TAG).d("Getting metadata for videoId: $videoId")
        return YouTube.player(videoId, playlistId, WEB_REMIX)
    }

    /**
     * Find best audio format based on quality preference
     */
    private fun findBestAudioFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        return playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.url != null }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus
            }
    }

    /**
     * Create synthetic format for NewPipe-extracted streams
     */
    private fun createSyntheticFormat(itag: Int, url: String): PlayerResponse.StreamingData.Format {
        val mimeType = when (itag) {
            251, 250, 249 -> "audio/webm; codecs=\"opus\""
            140, 139 -> "audio/mp4; codecs=\"mp4a.40.2\""
            else -> "audio/webm; codecs=\"opus\""
        }
        
        val bitrate = when (itag) {
            251 -> 160000
            250 -> 70000
            249 -> 50000
            140 -> 128000
            139 -> 48000
            else -> 128000
        }

        return PlayerResponse.StreamingData.Format(
            itag = itag,
            url = url,
            mimeType = mimeType,
            bitrate = bitrate,
            width = null,
            height = null,
            contentLength = null,
            quality = "medium",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrate,
            audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = null,
            audioSampleRate = 48000,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
            audioTrack = null,
        )
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(TAG).d("Force refresh for videoId: $videoId")
        // Reset any caches if needed
    }
}

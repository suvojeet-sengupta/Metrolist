package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Proxy

private class NewPipeDownloaderImpl(proxy: Proxy?) : Downloader() {

    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .method(httpMethod, dataToSend?.toRequestBody())
            .url(url)
            .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}

object NewPipeUtils {
    private const val TAG = "NewPipeUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    init {
        NewPipe.init(NewPipeDownloaderImpl(YouTube.proxy))
    }

    private fun log(message: String) {
        println("$TAG: $message")
    }

    fun getSignatureTimestamp(videoId: String): Result<Int> = runCatching {
        YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
    }

    fun getStreamUrl(format: PlayerResponse.StreamingData.Format, videoId: String): Result<String> =
        runCatching {
            val url = format.url ?: format.signatureCipher?.let { signatureCipher ->
                val params = parseQueryString(signatureCipher)
                val obfuscatedSignature = params["s"]
                    ?: throw ParsingException("Could not parse cipher signature")
                val signatureParam = params["sp"]
                    ?: throw ParsingException("Could not parse cipher signature parameter")
                val url = params["url"]?.let { URLBuilder(it) }
                    ?: throw ParsingException("Could not parse cipher url")
                url.parameters[signatureParam] =
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(
                        videoId,
                        obfuscatedSignature
                    )
                url.toString()
            } ?: throw ParsingException("Could not find format url")

            return@runCatching YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(
                videoId,
                url
            )
        }

    /**
     * Get stream URLs using NewPipe's full extraction pipeline.
     * This method handles age-restricted content and complex cipher decryption automatically.
     * Returns a map of itag to stream URL.
     */
    fun getStreamsFromNewPipe(videoId: String): Result<Map<Int, String>> = runCatching {
        log("Getting streams from NewPipe for videoId: $videoId")
        val streamInfo = StreamInfo.getInfo(
            NewPipe.getService(0),
            "https://www.youtube.com/watch?v=$videoId"
        )
        val streams = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
        log("NewPipe found ${streams.size} streams")
        streams.mapNotNull { stream ->
            val itag = stream.itagItem?.id ?: return@mapNotNull null
            val url = stream.content
            if (url.isNotBlank()) itag to url else null
        }.toMap()
    }

    /**
     * Get the best audio stream URL for a video using NewPipe extraction.
     * Returns the URL of the highest quality audio stream.
     */
    fun getBestAudioStreamUrl(videoId: String): Result<String> = runCatching {
        log("Getting best audio stream from NewPipe for videoId: $videoId")
        val streamInfo = StreamInfo.getInfo(
            NewPipe.getService(0),
            "https://www.youtube.com/watch?v=$videoId"
        )
        val bestAudio = streamInfo.audioStreams
            .maxByOrNull { it.averageBitrate }
            ?: throw ParsingException("No audio streams found")
        log("Best audio stream: bitrate=${bestAudio.averageBitrate}")
        bestAudio.content.takeIf { it.isNotBlank() }
            ?: throw ParsingException("Audio stream URL is empty")
    }

    /**
     * Extract player response with decoded URLs using NewPipe.
     * Similar to SimpMusic's newPipePlayer function.
     * 
     * @param videoId The video ID to extract
     * @param tempRes The original player response to copy metadata from
     * @return PlayerResponse with decoded stream URLs, or null if extraction fails
     */
    fun newPipePlayer(videoId: String, tempRes: PlayerResponse): PlayerResponse? {
        log("Attempting NewPipe player extraction for videoId: $videoId")
        log("Original playabilityStatus: ${tempRes.playabilityStatus.status}")
        
        // Even if status is not OK, try extraction - NewPipe might handle it
        val sigResponse = tempRes
        
        val streamsList = try {
            val streamInfo = StreamInfo.getInfo(
                NewPipe.getService(0),
                "https://www.youtube.com/watch?v=$videoId"
            )
            val allStreams = streamInfo.audioStreams + streamInfo.videoStreams + streamInfo.videoOnlyStreams
            allStreams.mapNotNull { stream ->
                val itag = stream.itagItem?.id ?: return@mapNotNull null
                val url = stream.content
                if (url.isNotBlank()) Pair(itag, url) else null
            }
        } catch (e: AgeRestrictedContentException) {
            log("Age-restricted content: $videoId")
            return null
        } catch (e: ContentNotAvailableException) {
            log("Content not available: $videoId - ${e.message}")
            return null
        } catch (e: Exception) {
            log("NewPipe extraction failed for $videoId: ${e.message}")
            return null
        }

        if (streamsList.isEmpty()) {
            log("NewPipe returned no streams")
            return null
        }

        log("NewPipe found ${streamsList.size} streams")

        // Copy the response with new URLs
        val decodedSigResponse = sigResponse.copy(
            streamingData = sigResponse.streamingData?.copy(
                formats = sigResponse.streamingData.formats?.map { format ->
                    format.copy(
                        url = streamsList.find { it.first == format.itag }?.second ?: format.url
                    )
                },
                adaptiveFormats = sigResponse.streamingData.adaptiveFormats.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url
                    )
                }
            )
        )

        // Verify we got valid URLs
        val allUrls = mutableListOf<String>()
        decodedSigResponse.streamingData?.adaptiveFormats?.mapNotNull { it.url }?.let { allUrls.addAll(it) }
        decodedSigResponse.streamingData?.formats?.mapNotNull { it.url }?.let { allUrls.addAll(it) }

        if (allUrls.isEmpty()) {
            log("No valid URLs after NewPipe extraction")
            return null
        }

        // Validate a random URL
        val randomUrl = allUrls.randomOrNull() ?: return null
        if (!validateUrl(randomUrl)) {
            log("URL validation failed")
            return null
        }

        log("NewPipe extraction successful")
        return decodedSigResponse
    }

    /**
     * Validate URL by checking if it returns 403
     */
    fun validateUrl(url: String): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .head()
                .url(url)
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                val isValid = it.code != 403
                log("URL validation: ${if (isValid) "OK" else "403"}")
                isValid
            }
        } catch (e: Exception) {
            log("URL validation failed: ${e.message}")
            false
        }
    }
}

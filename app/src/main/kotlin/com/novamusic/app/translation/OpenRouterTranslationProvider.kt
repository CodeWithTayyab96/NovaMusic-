/*
 * NovaMusic — OpenRouter translation provider.
 *
 * Adapted from Echo Music's OpenRouterService (GPL-3.0, same licence as NovaMusic): the
 * request shape, the "JSON array of lines" contract and the low temperature. NovaMusic
 * differences: explicit timeouts, structured errors instead of raw text, deterministic
 * chunking, and no streaming (this phase).
 *
 * Copyright (C) Echo Music contributors — GPL-3.0
 * Copyright (C) NovaMusic contributors — GPL-3.0
 */
package com.novamusic.app.translation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translates lyrics through an OpenRouter (OpenAI-compatible) chat-completions endpoint.
 *
 * Guarantees:
 * - a whole song costs ONE request in the common case, or a small deterministic number of
 *   chunks when it is long — never one request per line;
 * - every chunk is validated against its input line count, and the concatenated result is
 *   re-checked, so a partially translated song is never returned;
 * - HTTP failures become [TranslationError] categories, never raw provider text.
 *
 * @param apiKeyProvider read lazily on each call so a key changed in settings takes effect
 *   without rebuilding the provider. Never logged, never included in an error.
 */
class OpenRouterTranslationProvider(
    private val configProvider: suspend () -> TranslationConfig,
    private val maxCharsPerRequest: Int = LyricsTranslationContract.MAX_CHARS_PER_REQUEST,
) : TranslationProvider {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Explicit timeouts. A bare HttpClient() would inherit engine defaults — a 10s socket
    // timeout, which is how the updater's large download used to fail.
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    override fun isConfigured(): Boolean = true

    override suspend fun translate(request: TranslationRequest): Result<TranslationResult> {
        val config = configProvider()
        val apiKey = config.apiKey
        if (apiKey.isNullOrBlank()) {
            return failure(TranslationError.MissingApiKey)
        }
        if (request.lines.isEmpty()) {
            return Result.success(TranslationResult(emptyList()))
        }
        if (request.targetLanguage.isBlank()) {
            return failure(TranslationError.UnsupportedLanguage(""))
        }

        val chunks = LyricsTranslationContract.chunkLines(request.lines, maxCharsPerRequest)
        val accumulated = ArrayList<String>(request.lines.size)

        for (chunk in chunks) {
            val translated =
                translateChunk(
                    chunk = chunk,
                    targetLanguage = request.targetLanguage,
                    sourceLanguage = request.sourceLanguage,
                    apiKey = apiKey,
                    config = config,
                )
            // Any chunk failing fails the WHOLE translation. Never degrade to per-line calls.
            val lines = translated.getOrElse { return Result.failure(it) }
            accumulated.addAll(lines)
        }

        if (accumulated.size != request.lines.size) {
            return failure(
                TranslationError.InvalidTranslation(
                    "expected ${request.lines.size} lines, assembled ${accumulated.size}",
                ),
            )
        }
        return Result.success(TranslationResult(accumulated))
    }

    /** One request for one chunk, validated against that chunk's line count. */
    private suspend fun translateChunk(
        chunk: List<String>,
        targetLanguage: String,
        sourceLanguage: String?,
        apiKey: String,
        config: TranslationConfig,
    ): Result<List<String>> {
        val body =
            buildJsonObject {
                put("model", config.model)
                put("temperature", LyricsTranslationContract.TEMPERATURE)
                put(
                    "messages",
                    JsonArray(
                        listOf(
                            message("system", LyricsTranslationContract.buildSystemPrompt(targetLanguage, sourceLanguage)),
                            message("user", LyricsTranslationContract.buildUserPrompt(chunk)),
                        ),
                    ),
                )
            }

        val response: HttpResponse =
            try {
                client.post(config.baseUrl) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    // OpenRouter uses these for attribution; they are not secrets.
                    header("HTTP-Referer", "https://github.com/CodeWithTayyab96/NovaMusic-")
                    header("X-Title", "NovaMusic")
                    setBody(json.encodeToString(JsonObject.serializer(), body))
                }
            } catch (e: SocketTimeoutException) {
                return failure(TranslationError.Timeout)
            } catch (e: UnknownHostException) {
                return failure(TranslationError.NetworkUnavailable)
            } catch (e: IOException) {
                return failure(TranslationError.NetworkUnavailable)
            } catch (e: Exception) {
                return failure(TranslationError.Unknown(e.javaClass.simpleName))
            }

        // Map status codes to categories. No retry loop: a 429 is respected, not hammered.
        when (response.status.value) {
            200, 201 -> Unit
            401 -> return failure(TranslationError.Unauthorized)
            403 -> return failure(TranslationError.Forbidden)
            429 ->
                return failure(
                    TranslationError.RateLimited(
                        retryAfterSeconds = parseRetryAfter(response.headers["Retry-After"]),
                    ),
                )
            404 -> return failure(TranslationError.ProviderUnavailable(404))
            in 500..599 -> return failure(TranslationError.ProviderUnavailable(response.status.value))
            else -> return failure(TranslationError.ProviderUnavailable(response.status.value))
        }

        val raw = runCatching { response.bodyAsText() }.getOrNull()
        val content =
            extractMessageContent(raw)
                ?: return failure(TranslationError.InvalidResponse)

        return LyricsTranslationContract.parseTranslationArray(content, chunk.size)
    }

    /** Pulls choices[0].message.content out of an OpenAI-compatible response. */
    private fun extractMessageContent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val choices = root["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull()?.jsonObject ?: return null
            val message = first["message"]?.jsonObject ?: return null
            message["content"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private fun message(role: String, content: String): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
        }

    /** Retry-After is either delta-seconds or an HTTP date; only the numeric form is used. */
    private fun parseRetryAfter(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun <T> failure(error: TranslationError): Result<T> =
        Result.failure(TranslationContractException(error))
}

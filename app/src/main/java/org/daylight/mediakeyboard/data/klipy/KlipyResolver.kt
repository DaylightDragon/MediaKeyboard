package org.daylight.mediakeyboard.data.klipy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.daylight.mediakeyboard.data.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class KlipyResolver : MediaResolver {

    override val serviceId = "klipy"
    override val displayName = "Klipy"

    // Основной паттерн: https://klipy.com/gifs/<slug>
    // Допускаем опциональный www., http/https, query/fragment.
    private val pattern = Regex(
        """^https?://(?:www\.)?klipy\.com/gifs/([A-Za-z0-9\-_]+)(?:[/?#].*)?$""",
        RegexOption.IGNORE_CASE
    )

    override fun detect(url: String): Boolean {
        val result = pattern.matches(url.trim())
        Log.d("Klipy", "detect('$url') -> $result")
        return result
    }

    override fun extractRef(url: String): ServiceRef? {
        val m = pattern.find(url.trim())
        Log.d("Klipy", "extractRef('$url') -> match=$m, slug=${m?.groupValues?.get(1)}")
        return m?.let {
            ServiceRef(serviceId, mapOf("slug" to it.groupValues[1]))
        }
    }

    override suspend fun resolve(ref: ServiceRef, apiKey: String?): ResolvedMedia {
        Log.d("Klipy", "resolve() called, apiKey=${apiKey?.take(6)}…, ref=${ref.params}")
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w("Klipy", "API key is missing or blank")
                throw MissingApiKeyException(serviceId)
            }
        val slug = ref.params["slug"]
            ?: throw IllegalArgumentException("klipy ref without slug")

        return withContext(Dispatchers.IO) {
            val encodedSlug = URLEncoder.encode(slug, "UTF-8")
            val url = URL("https://api.klipy.com/api/v1/$key/gifs/items?slugs=$encodedSlug")
            Log.d("Klipy", "GET $url")

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "MediaKeyboard/1.0 (Android)")
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw ApiException(serviceId, code, conn.errorStream?.bufferedReader()?.readText())
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d("Klipy", "HTTP $code, body=${body.take(500)}")
                parseResponse(body)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseResponse(body: String): ResolvedMedia {
        val root = JSONObject(body)
        if (!root.optBoolean("result", false)) {
            throw ApiException(serviceId, 200, "result=false")
        }
        val items = root
            .getJSONObject("data")
            .getJSONArray("data")
        if (items.length() == 0) {
            throw ApiException(serviceId, 200, "empty data array")
        }

        val item = items.getJSONObject(0)
        val file = item.getJSONObject("file")

        // Для превью берём sm.webp (лёгкий), для вставки — md.gif или hd.gif.
        // Fallback-цепочка на случай отсутствия конкретного формата.
        val previewUrl =
            file.optJSONObject("sm")?.optJSONObject("webp")?.optString("url")
                ?: file.optJSONObject("md")?.optJSONObject("webp")?.optString("url")
                ?: file.optJSONObject("sm")?.optJSONObject("gif")?.optString("url")
                ?: throw ApiException(serviceId, 200, "no preview url")

        val contentUrl =
            file.optJSONObject("md")?.optJSONObject("gif")?.optString("url")
                ?: file.optJSONObject("hd")?.optJSONObject("gif")?.optString("url")
                ?: previewUrl

        // Соотношение сторон: берём из превью-объекта
        val ratio = file.optJSONObject("sm")?.optJSONObject("webp")?.let { w ->
            val ww = w.optInt("width", 0)
            val hh = w.optInt("height", 0)
            if (ww > 0 && hh > 0) ww.toFloat() / hh else 1f
        } ?: 1f

        return ResolvedMedia(
            previewUrl = previewUrl,
            contentUrl = contentUrl,
            aspectRatio = ratio,
            title = item.optString("title").takeIf { it.isNotBlank() }
        )
    }
}

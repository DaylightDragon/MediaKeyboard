package org.daylight.mediakeyboard.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class LocalMediaCacher(private val context: Context) {

    private val previewDir: File by lazy {
        File(context.filesDir, "previews").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Все веб-ссылки превью закэшируются локально один раз при добавлении,
     * чтобы не выполнять повторных сетевых запросов к API/CDN.
     */
    fun shouldCacheLocally(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    /**
     * Скачивает файл по URL в локальное хранилище приложения и возвращает `file:///...` путь.
     * Если файл уже был ранее скачан и он валидный, возвращает его локальный URI.
     */
    suspend fun cachePreview(previewUrl: String): String? = withContext(Dispatchers.IO) {
        if (!previewUrl.startsWith("http://") && !previewUrl.startsWith("https://")) {
            return@withContext null
        }

        val fileName = generateFileName(previewUrl)
        val targetFile = File(previewDir, fileName)

        // Если файл уже существует, но повреждён или пустой — удаляем его!
        if (targetFile.exists()) {
            if (targetFile.length() > 100) {
                Log.d("LocalMediaCacher", "File already cached: ${targetFile.absolutePath}")
                return@withContext "file://${targetFile.absolutePath}"
            } else {
                Log.w("LocalMediaCacher", "Removing corrupt/empty cached file: ${targetFile.absolutePath}")
                targetFile.delete()
            }
        }

        try {
            Log.d("LocalMediaCacher", "Downloading preview from: $previewUrl")
            var currentUrl = previewUrl
            var connection: HttpURLConnection? = null
            var redirectCount = 0

            while (redirectCount < 5) {
                val urlObj = URL(currentUrl)
                connection = urlObj.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                connection.setRequestProperty("Accept", "image/gif,image/webp,image/png,image/jpeg,image/*,*/*")
                connection.connect()

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307 || status == 308) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (!redirectUrl.isNullOrEmpty()) {
                        currentUrl = redirectUrl
                        redirectCount++
                        connection.disconnect()
                        continue
                    }
                }
                break
            }

            if (connection != null && connection.responseCode in 200..299) {
                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    Log.w("LocalMediaCacher", "Received HTML response instead of image for $previewUrl")
                    return@withContext null
                }

                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (targetFile.exists() && targetFile.length() > 100) {
                    Log.d("LocalMediaCacher", "Successfully cached (${targetFile.length()} bytes): ${targetFile.absolutePath}")
                    return@withContext "file://${targetFile.absolutePath}"
                } else {
                    Log.w("LocalMediaCacher", "Downloaded file too small (${targetFile.length()} bytes), deleting")
                    if (targetFile.exists()) targetFile.delete()
                }
            } else {
                Log.w("LocalMediaCacher", "HTTP error ${connection?.responseCode} while downloading $previewUrl")
            }
        } catch (e: Exception) {
            Log.e("LocalMediaCacher", "Failed to cache preview for $previewUrl", e)
            if (targetFile.exists()) {
                targetFile.delete()
            }
        }

        return@withContext null
    }

    private fun generateFileName(url: String): String {
        val cleanUrl = url.substringBefore("?")
        val extension = when {
            cleanUrl.endsWith(".gif", ignoreCase = true) -> ".gif"
            cleanUrl.endsWith(".webp", ignoreCase = true) -> ".webp"
            cleanUrl.endsWith(".png", ignoreCase = true) -> ".png"
            cleanUrl.endsWith(".jpg", ignoreCase = true) || cleanUrl.endsWith(".jpeg", ignoreCase = true) -> ".jpg"
            else -> ".media"
        }

        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        return "$hash$extension"
    }
}

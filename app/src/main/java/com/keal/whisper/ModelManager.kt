package com.keal.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    // Minimum sizes prevent an interrupted/partial previous download being treated as valid.
    private val minimumBytes = mapOf(
        "tiny-q5_1" to 25_000_000L,
        "base-q5_1" to 50_000_000L,
        "small-q5_1" to 160_000_000L,
        "medium-q5_0" to 450_000_000L,
        "large-v3-turbo-q5_0" to 500_000_000L,
        "large-v3-q5_0" to 950_000_000L
    )

    fun modelFile(context: Context, model: String) =
        File(File(context.filesDir, "models").apply { mkdirs() }, "ggml-$model.bin")

    fun ensureModel(context: Context, model: String, onProgress: (Int) -> Unit): File {
        val target = modelFile(context, model)
        val minimum = minimumBytes[model] ?: 1_000_000L
        if (target.exists() && target.length() >= minimum) {
            onProgress(100)
            return target
        }
        if (target.exists()) target.delete()

        val connection = URL(BASE_URL + "ggml-$model.bin?download=true")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        val temp = File(target.parentFile, target.name + ".download")

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Model download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            temp.delete()
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 99))
                    }
                    output.fd.sync()
                    if (total > 0 && copied != total) {
                        error("Model download was incomplete. Please retry on a stable connection.")
                    }
                    if (copied < minimum) {
                        error("Downloaded model file is too small. Please retry.")
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            return target
        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}

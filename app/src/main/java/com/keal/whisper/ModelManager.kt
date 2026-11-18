package com.keal.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    fun modelFile(context: Context, model: String) =
        File(File(context.filesDir, "models").apply { mkdirs() }, "ggml-$model.bin")

    fun ensureModel(context: Context, model: String, onProgress: (Int) -> Unit): File {
        val target = modelFile(context, model)
        if (target.exists() && target.length() > 1_000_000L) {
            onProgress(100)
            return target
        }
        val connection = (URL(BASE_URL + "ggml-$model.bin?download=true").openConnection() as HttpURLConnection)
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("Model download failed: HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            val temp = File(target.parentFile, target.name + ".download")
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            return target
        } finally {
            connection.disconnect()
        }
    }
}

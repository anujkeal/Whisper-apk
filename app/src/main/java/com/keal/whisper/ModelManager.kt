package com.keal.whisper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelManager {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    // Minimum sizes prevent an interrupted/partial previous download being treated as valid.
    private val expectedSha1 = mapOf(
        "tiny-q5_1" to "2827a03e495b1ed3048ef28a6a4620537db4ee51",
        "base-q5_1" to "a3733eda680ef76256db5fc5dd9de8629e62c5e7",
        "small-q5_1" to "6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771",
        "medium-q5_0" to "7718d4c1ec62ca96998f058114db98236937490e",
        "large-v3-turbo-q5_0" to "e050f7970618a659205450ad97eb95a18d69c9ee",
        "large-v3-q5_0" to "e6e2ed78495d403bef4b7cff42ef4aaadcfea8de"
    )

    private val minimumBytes = mapOf(
        "tiny-q5_1" to 25_000_000L,
        "base-q5_1" to 50_000_000L,
        "small-q5_1" to 160_000_000L,
        "medium-q5_0" to 450_000_000L,
        "large-v3-turbo-q5_0" to 500_000_000L,
        "large-v3-q5_0" to 950_000_000L
    )

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isValid(file: File, model: String): Boolean {
        val expected = expectedSha1[model] ?: return file.exists() && file.length() > 1_000_000L
        return file.exists() && file.length() >= (minimumBytes[model] ?: 1_000_000L) &&
            sha1(file).equals(expected, ignoreCase = true)
    }

    fun modelFile(context: Context, model: String) =
        File(File(context.filesDir, "models").apply { mkdirs() }, "ggml-$model.bin")

    fun ensureModel(context: Context, model: String, onProgress: (Int) -> Unit): File {
        val target = modelFile(context, model)
        val minimum = minimumBytes[model] ?: 1_000_000L
        if (isValid(target, model)) {
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
                    val expected = expectedSha1[model]
                    if (expected != null && !sha1(temp).equals(expected, ignoreCase = true)) {
                        error("Model integrity check failed. Delete the download and retry.")
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

package com.keal.whisper

object WhisperNative {
    init { System.loadLibrary("whisper-jni") }

    private var loadedPath: String? = null
    private var loadedContext: Long = 0L

    /**
     * Keep one model resident for repeat transcriptions. Loading a large model is
     * expensive; reuse it until the user chooses a different model or the process exits.
     */
    @Synchronized
    fun getOrLoadModel(modelPath: String): Long {
        if (loadedContext != 0L && loadedPath == modelPath) return loadedContext

        if (loadedContext != 0L) {
            releaseModel(loadedContext)
            loadedContext = 0L
            loadedPath = null
        }

        val context = initModel(modelPath)
        if (context != 0L) {
            loadedContext = context
            loadedPath = modelPath
        }
        return context
    }

    external fun initModel(modelPath: String): Long
    external fun transcribe(
        context: Long,
        samples: FloatArray,
        language: String,
        wordMode: Int,
        performanceMode: Int
    ): String
    external fun releaseModel(context: Long)
}

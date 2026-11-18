package com.keal.whisper

object WhisperNative {
    init { System.loadLibrary("whisper-jni") }
    external fun initModel(modelPath: String): Long
    external fun transcribe(context: Long, samples: FloatArray, language: String, wordMode: Int): String
    external fun releaseModel(context: Long)
}

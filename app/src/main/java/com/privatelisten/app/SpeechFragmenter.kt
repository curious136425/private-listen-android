package com.privatelisten.app

data class SpeechFragment(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

/** Produces short, exact substrings so a pause only repeats the current short fragment. */
object SpeechFragmenter {
    const val MAX_LENGTH = 120

    fun next(text: String, requestedOffset: Int): SpeechFragment? {
        val offset = requestedOffset.coerceIn(0, text.length)
        if (offset >= text.length) return null
        val fragment = TextChunker.split(text.substring(offset), MAX_LENGTH).first()
        return SpeechFragment(
            text = fragment,
            startOffset = offset,
            endOffset = offset + fragment.length,
        )
    }
}

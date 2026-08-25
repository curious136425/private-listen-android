package com.privatelisten.app

/** Splits text without deleting, inserting, reordering, or normalizing any character. */
object TextChunker {
    const val DEFAULT_MAX_LENGTH = 500

    private val lineBreaks = charArrayOf('\n', '\r')
    private val sentenceEndings = charArrayOf('。', '？', '！', '?', '!')

    fun split(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + maxLength, text.length)
            if (hardEnd == text.length) {
                result += text.substring(start)
                break
            }

            val lineBoundary = lastBoundary(text, start, hardEnd, lineBreaks)
            val sentenceBoundary = lastBoundary(text, start, hardEnd, sentenceEndings)
            val end = when {
                lineBoundary > start -> lineBoundary
                sentenceBoundary > start -> sentenceBoundary
                else -> hardEnd
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }

    /** Returns the exclusive end index, so punctuation remains in the preceding chunk. */
    private fun lastBoundary(
        text: String,
        start: Int,
        endExclusive: Int,
        boundaries: CharArray,
    ): Int {
        for (index in endExclusive - 1 downTo start) {
            if (text[index] in boundaries) return index + 1
        }
        return -1
    }
}

package com.privatelisten.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun `empty text produces no chunks`() {
        assertTrue(TextChunker.split("").isEmpty())
    }

    @Test
    fun `all original characters are preserved in order`() {
        val source = "第一段。\r\n第二段？  空格也保留！\n第三段没有结尾".repeat(180)
        val chunks = TextChunker.split(source, maxLength = 80)

        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.isNotEmpty() })
        assertTrue(chunks.all { it.length <= 80 })
    }

    @Test
    fun `five thousand Chinese characters are neither lost nor duplicated`() {
        val source = buildString {
            repeat(250) { index ->
                append("这是第${index + 1}小段，用来验证中文长文本连续切分是否可靠。")
                if (index % 4 == 0) append('\n') else append('！')
            }
        }
        val chunks = TextChunker.split(source)

        assertTrue(source.length >= 5_000)
        assertEquals(source, chunks.joinToString(separator = ""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= TextChunker.DEFAULT_MAX_LENGTH })
    }

    @Test
    fun `hard limit works when there is no punctuation`() {
        val source = "长".repeat(1_234)
        val chunks = TextChunker.split(source, maxLength = 500)

        assertEquals(listOf(500, 500, 234), chunks.map { it.length })
        assertEquals(source, chunks.joinToString(separator = ""))
    }
}

package com.privatelisten.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFragmenterTest {
    @Test
    fun `fragments reconstruct display segment exactly`() {
        val source = "第一句用于测试。第二句也要保留标点！带换行的第三句。\n".repeat(20)
        val fragments = mutableListOf<SpeechFragment>()
        var offset = 0
        while (true) {
            val fragment = SpeechFragmenter.next(source, offset) ?: break
            fragments += fragment
            offset = fragment.endOffset
        }

        assertEquals(source, fragments.joinToString("") { it.text })
        assertTrue(fragments.all { it.text.length <= SpeechFragmenter.MAX_LENGTH })
        assertEquals(source.length, offset)
    }

    @Test
    fun `resume offset starts at the saved short fragment`() {
        val source = "甲".repeat(120) + "乙".repeat(120) + "丙".repeat(30)
        val resumed = SpeechFragmenter.next(source, 120)

        assertEquals(120, resumed?.startOffset)
        assertEquals("乙".repeat(120), resumed?.text)
        assertEquals(240, resumed?.endOffset)
        assertNull(SpeechFragmenter.next(source, source.length))
    }
}

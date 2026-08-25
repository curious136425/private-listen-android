package com.privatelisten.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    @Test
    fun parsesMetadataSpineOrderAndReadableText() {
        val result = EpubParser.parse(epub())

        assertEquals("测试书", result.title)
        assertEquals("测试作者", result.author)
        assertEquals(BookSourceType.EPUB, result.sourceType)
        assertEquals(listOf("第一章", "第二章"), result.chapters.map { it.title })
        assertEquals("第一章\n第一段。\n第二段！", result.chapters[0].text)
        assertEquals("正文二。", result.chapters[1].text)
    }

    @Test
    fun rejectsEncryptedEpub() {
        val encrypted = epub(mapOf("META-INF/encryption.xml" to "<encryption/>"))
        assertThrows(IllegalArgumentException::class.java) { EpubParser.parse(encrypted) }
    }

    private fun epub(extra: Map<String, String> = emptyMap()): ByteArrayInputStream {
        val files = linkedMapOf(
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles>
                </container>
            """.trimIndent(),
            "OPS/book.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>测试书</dc:title><dc:creator>测试作者</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="two" href="chapter2.xhtml"/>
                    <item id="one" href="text/chapter1.xhtml"/>
                  </manifest>
                  <spine><itemref idref="one"/><itemref idref="two"/></spine>
                </package>
            """.trimIndent(),
            "OPS/text/chapter1.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head>
                <body><h1>第一章</h1><p>第一段。</p><p>第二段！</p></body></html>
            """.trimIndent(),
            "OPS/chapter2.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章</title></head>
                <body><p>正文二。</p></body></html>
            """.trimIndent(),
        ).apply { putAll(extra) }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}

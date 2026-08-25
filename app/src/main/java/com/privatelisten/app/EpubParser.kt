package com.privatelisten.app

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object EpubParser {
    private const val MAX_MARKUP_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_MARKUP_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 20_000
    private const val MAX_CHAPTER_COUNT = 5_000

    fun parse(input: InputStream, fallbackTitle: String = "未命名书籍"): ImportedBook {
        val entries = readMarkupEntries(input)
        require(entries.keys.none { it.equals("META-INF/encryption.xml", ignoreCase = true) }) {
            "检测到加密内容；0.2.0 不支持 DRM 或加密 EPUB"
        }
        val container = entries["META-INF/container.xml"] ?: error("EPUB 缺少 META-INF/container.xml")
        val rootFile = firstElement(parseXml(container), "rootfile")
            ?.getAttribute("full-path")
            ?.takeIf(String::isNotBlank)
            ?: error("EPUB 没有声明 OPF 内容文件")
        val opfPath = normalizeArchivePath(rootFile)
        val opf = entries[opfPath] ?: error("无法读取 EPUB 目录文件：$opfPath")
        val document = parseXml(opf)

        val title = firstText(document, "title").ifBlank { fallbackTitle }
        val author = elements(document, "creator")
            .map(Element::getTextContent)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("、")
            .ifBlank { null }

        val manifest = elements(document, "item").associate { item ->
            item.getAttribute("id") to item.getAttribute("href")
        }
        val spineIds = elements(document, "itemref")
            .map { it.getAttribute("idref") }
            .filter(String::isNotBlank)
        require(spineIds.isNotEmpty()) { "EPUB 目录中没有可读取的正文顺序" }
        require(spineIds.size <= MAX_CHAPTER_COUNT) { "EPUB 章节数量过多" }

        val chapters = spineIds.mapNotNull { id ->
            val href = manifest[id]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val chapterPath = resolveRelative(opfPath, href)
            val bytes = entries[chapterPath] ?: return@mapNotNull null
            val chapterDocument = runCatching { parseXml(bytes) }.getOrNull()
            val text = chapterDocument?.let(::extractReadableText)
                ?.ifBlank { null }
                ?: fallbackHtmlToText(bytes.toString(Charsets.UTF_8))
            if (text.isBlank()) return@mapNotNull null
            val chapterTitle = chapterDocument?.let(::extractChapterTitle)
                ?.ifBlank { null }
                ?: "第 ${spineIds.indexOf(id) + 1} 章"
            ChapterDraft(chapterTitle, text)
        }
        require(chapters.isNotEmpty()) { "EPUB 中没有提取到可朗读正文" }
        return ImportedBook(title, author, BookSourceType.EPUB, chapters)
    }

    private fun readMarkupEntries(input: InputStream): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var totalBytes = 0
        var entryCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRY_COUNT) { "EPUB 文件条目数量异常" }
                if (entry.isDirectory) continue
                val path = normalizeArchivePath(entry.name)
                if (!isMarkup(path)) continue
                val bytes = readLimited(zip, MAX_MARKUP_ENTRY_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_MARKUP_BYTES) { "EPUB 正文数据过大" }
                result[path] = bytes
            }
        }
        return result
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "EPUB 单个正文文件过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isMarkup(path: String): Boolean {
        val lower = path.lowercase()
        return lower == "meta-inf/container.xml" ||
            lower == "meta-inf/encryption.xml" ||
            lower.endsWith(".opf") || lower.endsWith(".xml") || lower.endsWith(".ncx") ||
            lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
            lower.endsWith(".xht")
    }

    private fun normalizeArchivePath(path: String): String {
        val replaced = path.replace('\\', '/')
        require(!replaced.startsWith('/')) { "EPUB 包含非法绝对路径" }
        val normalized = Paths.get(replaced).normalize().toString().replace('\\', '/')
        require(normalized != ".." && !normalized.startsWith("../")) { "EPUB 包含非法路径" }
        return normalized
    }

    private fun resolveRelative(opfPath: String, href: String): String {
        val encodedPath = href.substringBefore('#').substringBefore('?').replace("+", "%2B")
        val decoded = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name())
        val parent = Paths.get(opfPath).parent ?: Paths.get("")
        return normalizeArchivePath(parent.resolve(decoded).normalize().toString())
    }

    private fun parseXml(bytes: ByteArray): Document {
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "EPUB XML 包含不受支持的 DOCTYPE"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun elements(document: Document, localName: String): List<Element> {
        val nodes = document.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun firstElement(document: Document, localName: String): Element? =
        elements(document, localName).firstOrNull()

    private fun firstText(document: Document, localName: String): String =
        firstElement(document, localName)?.textContent?.trim().orEmpty()

    private fun extractChapterTitle(document: Document): String {
        for (name in listOf("h1", "h2", "h3", "title")) {
            firstText(document, name).takeIf(String::isNotBlank)?.let { return it }
        }
        return ""
    }

    private fun extractReadableText(document: Document): String {
        val output = StringBuilder()
        appendNode(document.documentElement, output)
        return normalizeReadableText(output.toString())
    }

    private fun appendNode(node: Node, output: StringBuilder) {
        if (node.nodeType == Node.TEXT_NODE) {
            output.append(node.nodeValue)
            return
        }
        val name = node.localName?.lowercase().orEmpty()
        if (name in setOf("script", "style", "svg", "nav", "head")) return
        if (name == "br") output.append('\n')
        val block = name in setOf(
            "p", "div", "section", "article", "aside", "header", "footer",
            "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "tr",
        )
        if (block && output.isNotEmpty() && output.last() != '\n') output.append('\n')
        var child = node.firstChild
        while (child != null) {
            appendNode(child, output)
            child = child.nextSibling
        }
        if (block && output.isNotEmpty() && output.last() != '\n') output.append('\n')
    }

    private fun normalizeReadableText(text: String): String = text
        .replace('\u00A0', ' ')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.replace(Regex("[\\t\\u000B\\u000C ]+"), " ").trim() }
        .filter(String::isNotBlank)
        .joinToString("\n")

    private fun fallbackHtmlToText(html: String): String {
        val withLines = html
            .replace(Regex("(?is)<(script|style|svg|nav)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(p|div|section|article|h[1-6]|li|blockquote|tr)[^>]*>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), "")
        return normalizeReadableText(decodeBasicEntities(withLines))
    }

    private fun decodeBasicEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
        result = Regex("&#(\\d+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull()?.let { Character.toChars(it).concatToString() }
                ?: match.value
        }
        return Regex("&#x([0-9a-fA-F]+);").replace(result) { match ->
            match.groupValues[1].toIntOrNull(16)?.let { Character.toChars(it).concatToString() }
                ?: match.value
        }
    }
}

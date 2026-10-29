package com.weifan.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * docx 读写。docx 本质是个 zip，正文在 word/document.xml 里。
 *
 * 纯 JDK 实现（java.util.zip + javax.xml），不引任何三方库 —— 与扩展侧 src/doc/doc.js 同一套算法。
 * 保留段落结构与数量，字体、表格、分栏不保留。
 */
object Docx {

    data class Doc(
        val entries: Map<String, ByteArray>,
        val documentXml: String,
        /** 所有段落的文字（含空段落，索引与文档一一对应） */
        val paragraphs: List<String>
    )

    /** 读出所有段落的文字 */
    fun read(bytes: ByteArray): Doc {
        val entries = unzip(bytes)
        val xml = entries["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: throw IOException("这不是一个 Word 文档（缺少 word/document.xml）")
        return Doc(entries, xml, extractParagraphs(xml))
    }

    fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return out
    }

    /** 按 <w:p> 切段，取每段的文字 */
    fun extractParagraphs(xml: String): List<String> {
        val dom = parse(xml)
        val nodes = dom.getElementsByTagName("w:p")
        val result = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            result.add(paragraphText(nodes.item(i) as Element))
        }
        return result
    }

    private fun paragraphText(paragraph: Element): String {
        val texts = paragraph.getElementsByTagName("w:t")
        val sb = StringBuilder()
        for (i in 0 until texts.length) {
            sb.append(texts.item(i).textContent)
        }
        return sb.toString().trim()
    }

    /** 把译文写回段落并重新打包成 docx */
    fun write(doc: Doc, translated: List<String>): ByteArray {
        val dom = parse(doc.documentXml)
        val nodes = dom.getElementsByTagName("w:p")

        for (i in 0 until nodes.length) {
            if (i >= translated.size) break
            val paragraph = nodes.item(i) as Element
            if (paragraphText(paragraph).isEmpty()) continue
            val replacement = translated[i]
            if (replacement.isEmpty()) continue
            setParagraphText(paragraph, replacement)
        }

        val entries = doc.entries.toMutableMap()
        entries["word/document.xml"] = serialize(dom).toByteArray(Charsets.UTF_8)
        return zip(entries)
    }

    /** 第一段保留原样式，其余清空 */
    private fun setParagraphText(paragraph: Element, text: String) {
        val texts = paragraph.getElementsByTagName("w:t")
        if (texts.length == 0) return
        texts.item(0).textContent = text
        for (i in 1 until texts.length) {
            texts.item(i).textContent = ""
        }
    }

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun serialize(dom: org.w3c.dom.Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty("encoding", "UTF-8")
        transformer.setOutputProperty("standalone", "yes")
        val writer = StringWriter()
        transformer.transform(DOMSource(dom), StreamResult(writer))
        return writer.toString()
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}

package com.gilrtano.vpn

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

object DocxTextExtractor {
    private const val MAX_XML_BYTES = 12 * 1024 * 1024

    fun read(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть выбранный DOCX")
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_XML_BYTES) { "DOCX слишком большой для импорта" }
                            out.write(buffer, 0, n)
                        }
                        return extractParagraphText(out.toByteArray())
                    }
                }
            }
        }
        error("В DOCX не найден word/document.xml")
    }

    private fun extractParagraphText(xml: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setInput(xml.inputStream(), "UTF-8")
        val result = StringBuilder()
        var event = parser.eventType
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> if (result.isNotEmpty() && result.last() != '\\n') result.append('\\n')
                    "t" -> inText = true
                    "tab" -> result.append('\\t')
                    "br" -> result.append('\\n')
                }
                XmlPullParser.TEXT -> if (inText) result.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "t") inText = false
            }
            event = parser.next()
        }
        return result.toString().trim()
    }
}

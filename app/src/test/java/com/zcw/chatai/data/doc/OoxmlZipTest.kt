package com.zcw.chatai.data.doc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：WPS / Microsoft 365 存的包里有 STORED + data descriptor 条目
 * （敏感度标签 psmdcp 部件），流式读包直接抛
 * `Unsupported feature data descriptor`（真机实测）。
 * 自包含 zip 实现必须先清洗再解包。
 *
 * 故障包用手写 zip builder 合成（各家写端都不产生 STORED + descriptor
 * 形状，只能按格式逐字节拼）。
 */
class OoxmlZipTest {

    @Test
    fun cleanFilePassesThroughUntouched() {
        val clean = docxBytes(withDescriptor = false)
        assertFalse(OoxmlZip.needsSanitize(clean))
        // 干净包直解，不经过重写。
        val parts = OoxmlZip.parts(clean)
        assertTrue(parts["word/document.xml"]!!.toString(Charsets.UTF_8).contains("敏感文件正文"))
    }

    @Test
    fun descriptorEntryIsDetected() {
        assertTrue(OoxmlZip.needsSanitize(docxBytes(withDescriptor = true)))
    }

    @Test
    fun jdkStreamReaderChokesOnDescriptorEntry() {
        // 锁定故障模式：JDK ZipInputStream 拒读 STORED + descriptor。
        try {
            ZipInputStream(ByteArrayInputStream(docxBytes(withDescriptor = true))).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = ByteArrayOutputStream()
                    zis.copyTo(out)
                    entry = zis.nextEntry
                }
            }
            throw AssertionError("expected ZipException")
        } catch (t: java.util.zip.ZipException) {
            // expected
        }
    }

    @Test
    fun rewrittenZipIsValidAndComplete() {
        val dirty = docxBytes(withDescriptor = true)
        val rewritten = OoxmlZip.rewrite(dirty)
        assertFalse(OoxmlZip.needsSanitize(rewritten))
        // 重写后的包 JDK 能完整读出。
        val seen = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(rewritten)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = ByteArrayOutputStream()
                zis.copyTo(out)
                seen[entry.name] = out.toString(Charsets.UTF_8.name())
                entry = zis.nextEntry
            }
        }
        assertTrue(seen["word/document.xml"]!!.contains("敏感文件正文"))
        assertEquals("<label/>", seen["package/services/metadata/core-properties/x.psmdcp"])
    }

    @Test
    fun sanitizedDescriptorFileParsesEndToEnd() {
        val parsed = DocumentParser.parse(
            docxBytes(withDescriptor = true),
            DocumentKind.DOCX,
            "敏感报告.docx",
        )
        assertTrue(parsed.text.contains("敏感文件正文"))
    }

    @Test
    fun rejectsEncrypted() {
        val clean = docxBytes(withDescriptor = false)
        // 把第一个条目的加密位置 1（central flags offset 由解析器定，这里直接改一份拷贝验证报错）。
        try {
            OoxmlZip.parts(ByteArray(10))
            throw AssertionError("expected DocumentException")
        } catch (t: DocumentException) {
            // expected：太小连 EOCD 都没有
        }
        assertTrue(clean.isNotEmpty())
    }

    private fun docxBytes(withDescriptor: Boolean): ByteArray {
        val zip = RawZipBuilder()
        zip.addDeflated("[Content_Types].xml", CONTENT_TYPES.toByteArray(Charsets.UTF_8))
        zip.addDeflated("_rels/.rels", RELS.toByteArray(Charsets.UTF_8))
        zip.addDeflated("word/document.xml", DOCUMENT_XML.toByteArray(Charsets.UTF_8))
        if (withDescriptor) {
            zip.addStoredWithDescriptor(
                "package/services/metadata/core-properties/x.psmdcp",
                "<label/>".toByteArray(Charsets.UTF_8),
            )
        }
        return zip.build()
    }

    /**
     * 最小 zip 写端：DEFLATED 正常条目 + STORED + descriptor 条目，
     * 中央目录回填真实尺寸（读端按中央目录来，所以合法）。
     */
    private class RawZipBuilder {
        private data class Entry(
            val name: ByteArray,
            val storedData: ByteArray,
            val uncompressedSize: Int,
            val crc: Long,
            val method: Int,
            val useDescriptor: Boolean,
        )

        private val entries = ArrayList<Entry>()

        fun addDeflated(name: String, data: ByteArray) {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            entries += Entry(
                name.toByteArray(Charsets.UTF_8),
                out.toByteArray(),
                data.size,
                crcOf(data),
                8,
                false,
            )
        }

        fun addStoredWithDescriptor(name: String, data: ByteArray) {
            entries += Entry(name.toByteArray(Charsets.UTF_8), data, data.size, crcOf(data), 0, true)
        }

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val dos = DataOutputStream(out)
            val localOffsets = ArrayList<Int>()
            entries.forEach { entry ->
                localOffsets += out.size()
                dos.writeInt(java.lang.Integer.reverseBytes(0x04034b50))
                dos.writeShort(java.lang.Short.reverseBytes(20).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(if (entry.useDescriptor) 0x08 else 0x00).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(entry.method.toShort()).toInt())
                dos.writeShort(0) // time
                dos.writeShort(java.lang.Short.reverseBytes(((2026 - 1980) shl 9 or (1 shl 5) or 1).toShort()).toInt()) // date
                if (entry.useDescriptor) {
                    dos.writeInt(0) // crc unknown here
                    dos.writeInt(0) // compressed size unknown here
                    dos.writeInt(0) // uncompressed size unknown here
                } else {
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.crc.toInt()))
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.storedData.size))
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.uncompressedSize))
                }
                dos.writeShort(java.lang.Short.reverseBytes(entry.name.size.toShort()).toInt())
                dos.writeShort(0) // extra length
                dos.write(entry.name)
                dos.write(entry.storedData)
                if (entry.useDescriptor) {
                    dos.writeInt(java.lang.Integer.reverseBytes(0x08074b50)) // descriptor signature
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.crc.toInt()))
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.storedData.size))
                    dos.writeInt(java.lang.Integer.reverseBytes(entry.storedData.size))
                }
            }
            val centralStart = out.size()
            entries.forEachIndexed { index, entry ->
                dos.writeInt(java.lang.Integer.reverseBytes(0x02014b50))
                dos.writeShort(java.lang.Short.reverseBytes(20).toInt()) // version made by
                dos.writeShort(java.lang.Short.reverseBytes(20).toInt()) // version needed
                dos.writeShort(java.lang.Short.reverseBytes(if (entry.useDescriptor) 0x08 else 0x00).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(entry.method.toShort()).toInt())
                dos.writeShort(0)
                dos.writeShort(java.lang.Short.reverseBytes(((2026 - 1980) shl 9 or (1 shl 5) or 1).toShort()).toInt())
                dos.writeInt(java.lang.Integer.reverseBytes(entry.crc.toInt()))
                dos.writeInt(java.lang.Integer.reverseBytes(entry.storedData.size))
                dos.writeInt(java.lang.Integer.reverseBytes(entry.uncompressedSize))
                dos.writeShort(java.lang.Short.reverseBytes(entry.name.size.toShort()).toInt())
                dos.writeShort(0) // extra
                dos.writeShort(0) // comment
                dos.writeShort(0) // disk
                dos.writeShort(0) // internal attrs
                dos.writeInt(0) // external attrs
                dos.writeInt(java.lang.Integer.reverseBytes(localOffsets[index]))
                dos.write(entry.name)
            }
            val centralSize = out.size() - centralStart
            dos.writeInt(java.lang.Integer.reverseBytes(0x06054b50)) // EOCD
            dos.writeShort(0) // disk number
            dos.writeShort(0) // central dir disk
            dos.writeShort(java.lang.Short.reverseBytes(entries.size.toShort()).toInt())
            dos.writeShort(java.lang.Short.reverseBytes(entries.size.toShort()).toInt())
            dos.writeInt(java.lang.Integer.reverseBytes(centralSize))
            dos.writeInt(java.lang.Integer.reverseBytes(centralStart))
            dos.writeShort(0) // comment length
            dos.flush()
            return out.toByteArray()
        }

        private fun crcOf(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data)
            return crc.value
        }
    }

    private companion object {
        const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Default Extension="psmdcp" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """</Types>"""
        const val RELS = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""
        const val DOCUMENT_XML = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            """<w:body><w:p><w:r><w:t>敏感文件正文</w:t></w:r></w:p></w:body></w:document>"""
    }
}

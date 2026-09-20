package com.zcw.chatai.data.doc

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * OOXML zip 自包含读写（纯逻辑，JVM 单测覆盖，无第三方依赖）。
 *
 * 背景有两层，真机实测：
 * 1. WPS / Microsoft 365 存的包里常有 STORED + data descriptor 条目
 *    （敏感度标签 psmdcp 部件）；POI 的流式读包直接抛
 *    `Unsupported feature data descriptor`，而 JDK 的 ZipInputStream
 *    连读都拒绝（"only DEFLATED entries can have EXT descriptor"）。
 * 2. 真机 JAXP 无视覆盖、永远返回自带 Expat，POI 整栈不可用，
 *    所以 zip 与 XML 全部手写，不再依赖任何 zip/XML 库。
 *
 * 实现只认中央目录（权威尺寸）：先扫 local header 看 descriptor 位
 * （干净文件零拷贝直通），脏包则按中央目录重写一份干净 zip。
 * 防护：条目数/解压总量上限；加密与非主流压缩直接报可读错误。
 */
internal object OoxmlZip {

    /** 全包解出（名 → 未压缩字节）；脏包先清洗。 */
    fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val clean = if (needsSanitize(bytes)) rewrite(bytes) else bytes
        return extractAll(parseCentral(clean), clean)
    }

    /** 单部件读取（名 → 未压缩字节），不存在返回 null。 */
    fun readPart(bytes: ByteArray, name: String): ByteArray? = parts(bytes)[name]

    /**
     * 纯函数：扫描 local header，只要有一个条目标了 descriptor 位就清洗。
     * 遇到 descriptor 条目直接返回 true（它的压缩尺寸字段为 0，无法继续向后跳）。
     */
    internal fun needsSanitize(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset + LOCAL_HEADER_SIZE <= bytes.size) {
            if (readIntLe(bytes, offset) != LOCAL_HEADER_SIGNATURE) return false
            val flags = readShortLe(bytes, offset + 6)
            if (flags and FLAG_DATA_DESCRIPTOR != 0) return true
            val compressedSize = readIntLe(bytes, offset + 18).toLong() and 0xFFFFFFFFL
            val nameLength = readShortLe(bytes, offset + 26)
            val extraLength = readShortLe(bytes, offset + 28)
            val next = offset.toLong() + LOCAL_HEADER_SIZE + nameLength + extraLength + compressedSize
            if (next > bytes.size || next < 0) return true
            offset = next.toInt()
        }
        return false
    }

    /** 纯函数：按中央目录重写干净 zip（方法/数据原样，只回填真实尺寸并清 descriptor 位）。 */
    internal fun rewrite(bytes: ByteArray): ByteArray {
        val entries = parseCentral(bytes)
        val out = ByteArrayOutputStream(bytes.size)
        val newOffsets = ArrayList<Int>(entries.size)
        entries.forEach { entry ->
            newOffsets += out.size()
            writeIntLe(out, LOCAL_HEADER_SIGNATURE)
            writeShortLe(out, 20) // version needed
            writeShortLe(out, entry.flags and FLAG_DATA_DESCRIPTOR.inv())
            writeShortLe(out, entry.method)
            writeShortLe(out, entry.time)
            writeShortLe(out, entry.date)
            writeIntLe(out, entry.crc)
            writeIntLe(out, entry.compressedSize)
            writeIntLe(out, entry.uncompressedSize)
            writeShortLe(out, entry.name.size)
            writeShortLe(out, entry.extra.size)
            out.write(entry.name)
            out.write(entry.extra)
            out.write(entry.data(bytes))
        }
        val centralStart = out.size()
        entries.forEachIndexed { index, entry ->
            writeIntLe(out, CENTRAL_HEADER_SIGNATURE)
            writeShortLe(out, 20) // version made by
            writeShortLe(out, 20) // version needed
            writeShortLe(out, entry.flags and FLAG_DATA_DESCRIPTOR.inv())
            writeShortLe(out, entry.method)
            writeShortLe(out, entry.time)
            writeShortLe(out, entry.date)
            writeIntLe(out, entry.crc)
            writeIntLe(out, entry.compressedSize)
            writeIntLe(out, entry.uncompressedSize)
            writeShortLe(out, entry.name.size)
            writeShortLe(out, entry.extra.size)
            writeShortLe(out, 0) // comment
            writeShortLe(out, 0) // disk
            writeShortLe(out, 0) // internal attrs
            writeIntLe(out, 0) // external attrs
            writeIntLe(out, newOffsets[index])
            out.write(entry.name)
            out.write(entry.extra)
        }
        val centralSize = out.size() - centralStart
        writeIntLe(out, EOCD_SIGNATURE)
        writeShortLe(out, 0) // disk
        writeShortLe(out, 0) // central disk
        writeShortLe(out, entries.size)
        writeShortLe(out, entries.size)
        writeIntLe(out, centralSize)
        writeIntLe(out, centralStart)
        writeShortLe(out, 0) // comment
        return out.toByteArray()
    }

    private data class Entry(
        val name: ByteArray,
        val extra: ByteArray,
        val flags: Int,
        val method: Int,
        val time: Int,
        val date: Int,
        val crc: Long,
        val compressedSize: Int,
        val uncompressedSize: Long,
        /** 数据在源字节里的起点（local header 之后）。 */
        val dataOffset: Int,
    ) {
        fun nameText(): String = name.toString(Charsets.UTF_8)

        fun data(source: ByteArray): ByteArray =
            source.copyOfRange(dataOffset, dataOffset + compressedSize)
    }

    private fun parseCentral(bytes: ByteArray): List<Entry> {
        val eocd = findEocd(bytes)
            ?: throw DocumentException("文档已损坏（找不到 zip 目录）")
        val count = readShortLe(bytes, eocd + 8)
        val centralSize = readIntLe(bytes, eocd + 12).toLong() and 0xFFFFFFFFL
        val centralOffset = readIntLe(bytes, eocd + 16).toLong() and 0xFFFFFFFFL
        if (count > DocumentLimits.MAX_ZIP_PARTS) {
            throw DocumentException("文档部件过多（$count），可能是异常文件")
        }
        if (centralOffset + centralSize > bytes.size) {
            throw DocumentException("文档已损坏（zip 目录越界）")
        }
        val entries = ArrayList<Entry>(count.coerceAtMost(64))
        var offset = centralOffset.toInt()
        repeat(count) {
            if (offset + CENTRAL_HEADER_SIZE > bytes.size ||
                readIntLe(bytes, offset) != CENTRAL_HEADER_SIGNATURE
            ) {
                throw DocumentException("文档已损坏（zip 目录项非法）")
            }
            val flags = readShortLe(bytes, offset + 8)
            val method = readShortLe(bytes, offset + 10)
            if (flags and FLAG_ENCRYPTED != 0) {
                throw DocumentException("文档已加密，暂不支持解析")
            }
            if (method != METHOD_STORED && method != METHOD_DEFLATED) {
                throw DocumentException("文档用了不受支持的压缩方式，暂不支持解析")
            }
            val nameLength = readShortLe(bytes, offset + 28)
            val extraLength = readShortLe(bytes, offset + 30)
            val localOffset = readIntLe(bytes, offset + 42).toLong() and 0xFFFFFFFFL
            val nameEnd = offset + CENTRAL_HEADER_SIZE + nameLength + extraLength
            if (nameEnd > bytes.size || localOffset + LOCAL_HEADER_SIZE > bytes.size) {
                throw DocumentException("文档已损坏（zip 目录项越界）")
            }
            val name = bytes.copyOfRange(offset + CENTRAL_HEADER_SIZE, offset + CENTRAL_HEADER_SIZE + nameLength)
            val extra = bytes.copyOfRange(
                offset + CENTRAL_HEADER_SIZE + nameLength,
                offset + CENTRAL_HEADER_SIZE + nameLength + extraLength,
            )
            val local = localOffset.toInt()
            if (readIntLe(bytes, local) != LOCAL_HEADER_SIGNATURE) {
                throw DocumentException("文档已损坏（zip 数据区非法）")
            }
            val localNameLength = readShortLe(bytes, local + 26)
            val localExtraLength = readShortLe(bytes, local + 28)
            val dataOffset = local + LOCAL_HEADER_SIZE + localNameLength + localExtraLength
            val compressedSize = readIntLe(bytes, offset + 20).toLong() and 0xFFFFFFFFL
            if (compressedSize > Int.MAX_VALUE || dataOffset + compressedSize > bytes.size) {
                throw DocumentException("文档已损坏（zip 数据越界）")
            }
            entries += Entry(
                name = name,
                extra = extra,
                flags = flags,
                method = method,
                time = readShortLe(bytes, offset + 12),
                date = readShortLe(bytes, offset + 14),
                crc = readIntLe(bytes, offset + 16).toLong() and 0xFFFFFFFFL,
                compressedSize = compressedSize.toInt(),
                uncompressedSize = readIntLe(bytes, offset + 24).toLong() and 0xFFFFFFFFL,
                dataOffset = dataOffset.toInt(),
            )
            offset = nameEnd + readShortLe(bytes, offset + 32) // 跳过 comment
        }
        return entries
    }

    private fun extractAll(entries: List<Entry>, source: ByteArray): Map<String, ByteArray> {
        var total = 0L
        val result = LinkedHashMap<String, ByteArray>(entries.size.coerceAtLeast(16))
        entries.forEach { entry ->
            if (entry.uncompressedSize + total > DocumentLimits.MAX_ZIP_UNCOMPRESSED_BYTES) {
                throw DocumentException("文档解压后过大，暂不支持解析")
            }
            val raw = entry.data(source)
            val data = when (entry.method) {
                METHOD_STORED -> raw
                METHOD_DEFLATED -> inflate(raw, entry.uncompressedSize)
                else -> throw DocumentException("文档用了不受支持的压缩方式，暂不支持解析")
            }
            total += data.size
            result[entry.nameText()] = data
        }
        return result
    }

    private fun inflate(raw: ByteArray, expectedSize: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(expectedSize.coerceAtMost(8L * 1024 * 1024).toInt().coerceAtLeast(1024))
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    // 数据流提前见底：正常包只有空条目会走这里，其余一律是截断损坏。
                    if (out.size() > 0 || raw.isNotEmpty()) {
                        throw DocumentException("文档已损坏（解压失败）")
                    }
                    break
                }
                val read = inflater.inflate(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                if (out.size() > DocumentLimits.MAX_ZIP_UNCOMPRESSED_BYTES) {
                    throw DocumentException("文档解压后过大，暂不支持解析")
                }
            }
            return out.toByteArray()
        } catch (t: DocumentException) {
            throw t
        } catch (t: DataFormatException) {
            throw DocumentException("文档已损坏（解压失败）")
        } finally {
            inflater.end()
        }
    }

    private fun findEocd(bytes: ByteArray): Int? {
        // EOCD 最小 22 字节，注释最长 64KB；文件 ≤20MB，全尾扫描也便宜。
        var index = bytes.size - EOCD_MIN_SIZE
        val floor = maxOf(0, bytes.size - EOCD_MIN_SIZE - EOCD_MAX_COMMENT)
        while (index >= floor) {
            if (readIntLe(bytes, index) == EOCD_SIGNATURE) return index
            index--
        }
        return null
    }

    private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
    private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_MAX_COMMENT = 65535
    private const val FLAG_ENCRYPTED = 0x01
    private const val FLAG_DATA_DESCRIPTOR = 0x08
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8

    internal fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    internal fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeShortLe(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeIntLe(out: ByteArrayOutputStream, value: Long) {
        writeIntLe(out, value.toInt())
    }
}

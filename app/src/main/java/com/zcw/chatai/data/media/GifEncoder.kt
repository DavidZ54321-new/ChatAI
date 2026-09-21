package com.zcw.chatai.data.media

/**
 * 动画 GIF 编码器（纯 Kotlin，无依赖，JVM 可测）。
 *
 * GIF89a 子集：256 色全局/局部调色板（NeuQuant 量化）+ Netscape 循环块 + 每帧图形控制扩展
 * （delay，不透明、无透明色、无交错）。调用方按帧传 ARGB 像素，尺寸必须一致。
 *
 * 算法来源：Kevin Weiner 的公有领域 AnimatedGifEncoder（LZW）与 Anthony Dekker 的
 * 公有领域 NeuQuant 量化器；此处按原算法重写为 Kotlin，避免引入年久失修的第三方库。
 */
object GifEncoder {

    /** 一帧：[pixels] 为 ARGB（`0xAARRGGBB`），行优先，长度必须为 width*height。 */
    data class Frame(val pixels: IntArray, val delayMs: Int)

    /**
     * @param loopCount 0 = 无限循环，>0 = 循环次数。
     * @return 完整的 GIF 文件字节；输入非法时返回 null。
     */
    fun encode(width: Int, height: Int, frames: List<Frame>, loopCount: Int = 0): ByteArray? {
        if (width <= 0 || height <= 0 || frames.isEmpty()) return null
        if (frames.any { it.pixels.size != width * height }) return null
        val out = GifWriter()
        out.writeAscii("GIF89a")
        // 逻辑屏幕描述符：全局调色表标志 + 8 位色深 + 256 色表。
        out.writeShort(width)
        out.writeShort(height)
        out.write(0xF7)
        out.write(0) // 背景色索引
        out.write(0) // 像素宽高比
        // 全局调色表必须**紧接**逻辑屏幕描述符（GIF89a 规范），不能晚于任何扩展块：
        // 放别处解码器会从偏移 13 读走 768 字节的帧数据，整条流从此错位（不可解码）。
        val first = NeuQuant.quantize(frames.first().pixels)
        out.writeBytes(first.second)
        if (loopCount >= 0) {
            // Netscape 循环扩展。
            out.write(0x21); out.write(0xFF); out.write(11)
            out.writeAscii("NETSCAPE2.0")
            out.write(3); out.write(1)
            out.writeShort(loopCount)
            out.write(0)
        }
        frames.forEachIndexed { index, frame ->
            val (indexed, palette) = if (index == 0) first else NeuQuant.quantize(frame.pixels)
            // 图形控制扩展：delay（1/100s），无透明色、无处置要求。
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write(0)
            out.writeShort((frame.delayMs / 10).coerceIn(1, 65535))
            out.write(0)
            out.write(0)
            // 图像描述符：首帧用全局表，后续帧自带局部表（与全局表同内容，解码器兼容性最好）。
            out.write(0x2C)
            out.writeShort(0); out.writeShort(0)
            out.writeShort(width); out.writeShort(height)
            if (index == 0) {
                out.write(0)
            } else {
                out.write(0x87) // 局部调色表标志 + 256 色
                out.writeBytes(palette)
            }
            out.write(8) // LZW 最小码长
            Lzw.encode(indexed, out)
        }
        out.write(0x3B) // Trailer
        return out.toByteArray()
    }

    private class GifWriter {
        private val buf = mutableListOf<Byte>()
        fun write(b: Int) {
            buf += b.toByte()
        }
        fun writeShort(v: Int) {
            buf += (v and 0xFF).toByte()
            buf += ((v ushr 8) and 0xFF).toByte()
        }
        fun writeAscii(s: String) {
            for (c in s) buf += c.code.toByte()
        }
        fun writeBytes(a: ByteArray) {
            for (b in a) buf += b
        }
        fun writeBlock(data: ByteArray, offset: Int, length: Int) {
            write(length)
            for (i in 0 until length) buf += data[offset + i]
        }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    /**
     * NeuQuant 神经网络量化：ARGB 像素 → 256 色索引 + RGB 调色表。
     *
     * 网络节点存 [b, g, r]（12 位精度，即 8 位值 << 4）；调色表按 RGB 顺序输出。
     * 学习是 Kohonen SOM 的标准流程：全图步进采样 → 最近邻竞争 → 单点 + 邻域更新 →
     * alpha/radius 按 1/30 衰减；小尺寸 GIF（480p 级、几十帧）收敛足够。
     */
    private object NeuQuant {
        private const val NET_SIZE = 256
        private const val INIT_RADIUS = NET_SIZE shr 3 // 32
        private const val RADIUS_BIAS_SHIFT = 6
        private const val RADIUS_BIAS = 1 shl RADIUS_BIAS_SHIFT // 64
        private const val ALPHA_SHIFT = 10
        private const val INIT_ALPHA = 1 shl ALPHA_SHIFT // 1024
        private const val ALPHA_RADIUS_BIAS = 1 shl (ALPHA_SHIFT + RADIUS_BIAS_SHIFT) // 65536

        fun quantize(pixels: IntArray): Pair<ByteArray, ByteArray> {
            // 灰度轴均匀初始化。
            val network = Array(NET_SIZE) { i ->
                val v = (i shl 12) / NET_SIZE
                intArrayOf(v, v, v)
            }
            val sampleCount = pixels.size
            if (sampleCount == 0) return ByteArray(0) to ByteArray(NET_SIZE * 3)
            // 步进采样：约 3000 个采样点即可（小图全采，大图稀疏）。
            val step = maxOf(1, sampleCount / 3000)
            val totalSamples = sampleCount / step
            var delta = totalSamples / 100
            if (delta == 0) delta = 1
            var alpha = INIT_ALPHA
            var radius = INIT_RADIUS * RADIUS_BIAS
            var radPower = radiusPower(alpha, radius)
            var pi = 0
            var i = 0
            while (i < totalSamples) {
                val p = pixels[pi]
                val b = ((p ushr 0) and 0xFF) shl 4
                val g = ((p ushr 8) and 0xFF) shl 4
                val r = ((p ushr 16) and 0xFF) shl 4
                val best = nearest(network, b, g, r)
                alterSingle(alpha, best, network, b, g, r)
                if (radPower.isNotEmpty()) {
                    alterNeighbours(radPower, best, network, b, g, r)
                }
                pi += step
                if (pi >= sampleCount) pi -= sampleCount
                i++
                if (i % delta == 0) {
                    alpha -= alpha / 30
                    radius -= radius / 30
                    radPower = radiusPower(alpha, radius)
                }
            }
            // 调色表：RGB 顺序（GIF 要求）。
            val palette = ByteArray(NET_SIZE * 3)
            for (n in 0 until NET_SIZE) {
                palette[n * 3] = (network[n][2] shr 4).toByte()
                palette[n * 3 + 1] = (network[n][1] shr 4).toByte()
                palette[n * 3 + 2] = (network[n][0] shr 4).toByte()
            }
            val indexed = ByteArray(pixels.size)
            for (k in pixels.indices) {
                val p = pixels[k]
                indexed[k] = nearest(
                    network,
                    ((p ushr 0) and 0xFF) shl 4,
                    ((p ushr 8) and 0xFF) shl 4,
                    ((p ushr 16) and 0xFF) shl 4,
                ).toByte()
            }
            return indexed to palette
        }

        /** radius 衰减表（原实现的 radpower）：邻域权重随距离下降。 */
        private fun radiusPower(alpha: Int, radius: Int): IntArray {
            val rad = radius ushr RADIUS_BIAS_SHIFT
            if (rad <= 1) return IntArray(0)
            return IntArray(rad) { j ->
                alpha * (((rad * rad - j * j) * RADIUS_BIAS) / (rad * rad))
            }
        }

        private fun nearest(network: Array<IntArray>, b: Int, g: Int, r: Int): Int {
            var bestDist = Int.MAX_VALUE
            var best = 0
            for (n in 0 until NET_SIZE) {
                val node = network[n]
                val db = node[0] - b
                val dg = node[1] - g
                val dr = node[2] - r
                val dist = db * db + dg * dg + dr * dr
                if (dist < bestDist) {
                    bestDist = dist
                    best = n
                }
            }
            return best
        }

        private fun alterSingle(alpha: Int, i: Int, network: Array<IntArray>, b: Int, g: Int, r: Int) {
            val node = network[i]
            node[0] -= (alpha * (node[0] - b)) / INIT_ALPHA
            node[1] -= (alpha * (node[1] - g)) / INIT_ALPHA
            node[2] -= (alpha * (node[2] - r)) / INIT_ALPHA
        }

        private fun alterNeighbours(
            radPower: IntArray,
            i: Int,
            network: Array<IntArray>,
            b: Int,
            g: Int,
            r: Int,
        ) {
            val rad = radPower.size
            var lo = i - rad
            if (lo < -1) lo = -1
            var hi = i + rad
            if (hi > NET_SIZE) hi = NET_SIZE
            var j = i + 1
            var k = i - 1
            var m = 0
            while (j < hi || k > lo) {
                // m  Theorie 上 < radPower.size（j-i 与 i-k 都 < rad），做一次钳制防越界。
                val a = radPower[m.coerceAtMost(radPower.size - 1)]
                m++
                if (j < hi) {
                    val node = network[j++]
                    node[0] -= (a * (node[0] - b)) / ALPHA_RADIUS_BIAS
                    node[1] -= (a * (node[1] - g)) / ALPHA_RADIUS_BIAS
                    node[2] -= (a * (node[2] - r)) / ALPHA_RADIUS_BIAS
                }
                if (k > lo) {
                    val node = network[k--]
                    node[0] -= (a * (node[0] - b)) / ALPHA_RADIUS_BIAS
                    node[1] -= (a * (node[1] - g)) / ALPHA_RADIUS_BIAS
                    node[2] -= (a * (node[2] - r)) / ALPHA_RADIUS_BIAS
                }
            }
        }
    }

    /** LZW 压缩（GIF 变体）：最小码长 8，字典满 4096 时发 clear 重建，每 255 字节切子块。 */
    private object Lzw {
        fun encode(indexed: ByteArray, out: GifWriter) {
            val initCodeSize = 8
            val clearCode = 1 shl initCodeSize
            val eofCode = clearCode + 1
            var codeSize = initCodeSize + 1
            var nextCode = eofCode + 1
            val dict = HashMap<Int, Int>(4096)
            val pending = mutableListOf<Int>()
            var accum = 0
            var bits = 0
            fun output(code: Int) {
                accum = accum or (code shl bits)
                bits += codeSize
                while (bits >= 8) {
                    pending += accum and 0xFF
                    accum = accum ushr 8
                    bits -= 8
                }
            }
            fun flushPending() {
                var i = 0
                while (i < pending.size) {
                    val n = minOf(255, pending.size - i)
                    val block = ByteArray(n)
                    for (j in 0 until n) block[j] = pending[i + j].toByte()
                    out.writeBlock(block, 0, n)
                    i += n
                }
                pending.clear()
            }
            output(clearCode)
            var prefix = indexed[0].toInt() and 0xFF
            for (i in 1 until indexed.size) {
                val k = indexed[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val code = dict[key]
                if (code != null) {
                    prefix = code
                } else {
                    output(prefix)
                    if (nextCode < 4096) {
                        dict[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        output(clearCode)
                        dict.clear()
                        codeSize = initCodeSize + 1
                        nextCode = eofCode + 1
                    }
                    prefix = k
                }
                if (pending.size > 4000) flushPending()
            }
            output(prefix)
            output(eofCode)
            if (bits > 0) pending += accum and 0xFF
            flushPending()
            out.write(0) // 数据终结空子块
        }
    }
}

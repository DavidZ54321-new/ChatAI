package com.zcw.chatai.data.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 把分片 / 变体音频收成普通的非分片 M4A。
 * 服务端名单里有 m4a，拒掉的是这种容器；不解码成 WAV（体积会大一个数量级）。
 */
object AudioRepack {

    fun repack(source: File, dest: File): Boolean {
        if (!source.isFile || source.length() <= 0L) return false
        dest.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            val track = audioTrack(extractor) ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(sampleCapacity(format))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), sampleFlags(extractor.sampleFlags))
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            dest.isFile && dest.length() > 0L
        } catch (_: Throwable) {
            dest.delete()
            false
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun sampleCapacity(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        return declared.coerceIn(64 * 1024, 2 * 1024 * 1024)
    }

    private fun sampleFlags(extractorFlags: Int): Int {
        var flags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}

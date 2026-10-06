package com.zcw.chatai.ui.chat

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一条会话上次贴底时，最后一组的高度。对不上视口或正文就不用。 */
data class TailLayout(
    val groupKey: String,
    val heightPx: Int,
    val contentChars: Int,
    val widthPx: Int,
    val fontScaleMilli: Int,
    val themeFamily: String,
    val dark: Boolean,
) {
    fun matches(
        groupKey: String,
        contentChars: Int,
        widthPx: Int,
        fontScaleMilli: Int,
        themeFamily: String,
        dark: Boolean,
    ): Boolean = this.groupKey == groupKey &&
        this.contentChars == contentChars &&
        this.widthPx == widthPx &&
        this.fontScaleMilli == fontScaleMilli &&
        this.themeFamily == themeFamily &&
        this.dark == dark
}

/**
 * 还没定稿时，正文比记录矮就先占住上次的高度（冷启动 markdown 仍在加载）。
 * 定稿后以实测为准，不再占：展开收起、回合收尾变矮都要能收回，否则最小高度会粘成一块空白。
 * 流式中不占。
 */
fun reserveTailHeight(
    cachedPx: Int?,
    measuredPx: Int,
    streaming: Boolean,
    settled: Boolean,
): Boolean {
    if (streaming || settled || cachedPx == null || cachedPx <= 0) return false
    if (measuredPx <= 0) return true
    return measuredPx + TAIL_HEIGHT_SLOP_PX < cachedPx
}

/**
 * 只写定稿后的高度。[settledPx] 为正表示这一帧已经摆好；0 表示还没定稿，不写。
 * 比缓存矮也写，避免上一帧的高槽位粘住。
 */
fun shouldRecordTailHeight(
    located: Boolean,
    streaming: Boolean,
    settledPx: Int,
): Boolean = located && !streaming && settledPx > 0

fun tailContentChars(items: List<ChatMessageItem>): Int =
    items.sumOf { it.content.length + (it.reasoning?.length ?: 0) }

/** 最后一组是助手回合就用它，否则用用户那一组。 */
fun tailGroupKey(groups: List<MessageGroup>): String? = groups.lastOrNull()?.key

const val TAIL_HEIGHT_SLOP_PX = 16

fun encodeTailLayouts(entries: Map<String, TailLayout>): String =
    entries.entries.joinToString("\n") { (id, layout) ->
        listOf(
            id,
            layout.groupKey,
            layout.heightPx.toString(),
            layout.contentChars.toString(),
            layout.widthPx.toString(),
            layout.fontScaleMilli.toString(),
            layout.themeFamily,
            if (layout.dark) "1" else "0",
        ).joinToString("\t")
    }

fun decodeTailLayouts(text: String): Map<String, TailLayout> {
    if (text.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, TailLayout>()
    for (line in text.split('\n')) {
        if (line.isBlank()) continue
        val parts = line.split('\t')
        if (parts.size != 8) continue
        val height = parts[2].toIntOrNull() ?: continue
        val chars = parts[3].toIntOrNull() ?: continue
        val width = parts[4].toIntOrNull() ?: continue
        val font = parts[5].toIntOrNull() ?: continue
        if (parts[0].isBlank() || parts[1].isBlank() || parts[6].isBlank()) continue
        out[parts[0]] = TailLayout(
            groupKey = parts[1],
            heightPx = height,
            contentChars = chars,
            widthPx = width,
            fontScaleMilli = font,
            themeFamily = parts[6],
            dark = parts[7] == "1",
        )
    }
    return out
}

/**
 * 尾部高度：内存里一份，并写到应用私有目录。进程杀掉还能读回来。
 * 读失败就当没有，调用方退回普通贴底。
 */
class TailLayoutStore(
    private val file: File,
    private val io: CoroutineScope,
) {
    private val lock = Any()
    private val writeMutex = Mutex()
    private val entries = HashMap<String, TailLayout>()
    private var loaded = false

    fun lookup(
        conversationId: String,
        groupKey: String,
        contentChars: Int,
        widthPx: Int,
        fontScaleMilli: Int,
        themeFamily: String,
        dark: Boolean,
    ): Int? {
        val saved = synchronized(lock) {
            if (!loaded) return null
            entries[conversationId]
        } ?: return null
        if (!saved.matches(groupKey, contentChars, widthPx, fontScaleMilli, themeFamily, dark)) return null
        return saved.heightPx.takeIf { it > 0 }
    }

    fun record(conversationId: String, layout: TailLayout) {
        if (conversationId.isBlank() || layout.heightPx <= 0) return
        val changed = synchronized(lock) {
            if (!loaded) readFile()
            if (entries[conversationId] == layout) false else {
                entries[conversationId] = layout
                true
            }
        }
        if (!changed) return
        io.launch {
            writeMutex.withLock {
                val snapshot = synchronized(lock) { entries.toMap() }
                runCatching { file.writeText(encodeTailLayouts(snapshot)) }
            }
        }
    }

    /** 启动时调用一次。之后 [lookup] 只读内存。 */
    fun load() {
        synchronized(lock) { readFile() }
    }

    private fun readFile() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
        entries.putAll(decodeTailLayouts(text))
    }
}


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

/** 正文还比记录矮时，先占住上次的高度；追上了就撤掉占位。流式中不占。 */
fun reserveTailHeight(cachedPx: Int?, measuredPx: Int, streaming: Boolean): Boolean {
    if (streaming || cachedPx == null || cachedPx <= 0) return false
    if (measuredPx <= 0) return true
    return measuredPx + TAIL_HEIGHT_SLOP_PX < cachedPx
}

/** 贴底且这一轮不在流式时才把量到的高度写回去。占位还没被正文追上时不写，避免把半截高度存下去。 */
fun shouldRecordTailHeight(
    located: Boolean,
    streaming: Boolean,
    measuredPx: Int,
    reservedPx: Int?,
): Boolean {
    if (!located || streaming || measuredPx <= 0) return false
    if (reservedPx == null) return true
    return measuredPx + TAIL_HEIGHT_SLOP_PX >= reservedPx
}

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


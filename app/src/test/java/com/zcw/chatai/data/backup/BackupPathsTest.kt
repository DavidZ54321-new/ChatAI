package com.zcw.chatai.data.backup

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPathsTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun suggestsTimestampedFileName() {
        val timestamp = ZonedDateTime.of(2026, 9, 24, 15, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals("chatai-backup-20260924-1530.zip", BackupPaths.suggestedFileName(timestamp, utc))
    }

    @Test
    fun acceptsOrdinaryEntries() {
        assertTrue(BackupPaths.isSafeEntry("manifest.json"))
        assertTrue(BackupPaths.isSafeEntry("settings.json"))
        assertTrue(BackupPaths.isSafeEntry("conversations.json"))
        assertTrue(BackupPaths.isSafeEntry("messages.json"))
        assertTrue(BackupPaths.isSafeEntry("attachments/c1/a1.jpg"))
        assertTrue(BackupPaths.isSafeEntry("attachments/c1/a1.thumb.jpg"))
        assertTrue(BackupPaths.isSafeEntry("attachments/c1/a1.extracted.txt"))
    }

    /** zip-slip：备份是用户从外部选进来的文件，不能假定它善意。 */
    @Test
    fun rejectsPathTraversalAndAbsolutePaths() {
        assertFalse(BackupPaths.isSafeEntry(""))
        assertFalse(BackupPaths.isSafeEntry("/etc/passwd"))
        assertFalse(BackupPaths.isSafeEntry("\\windows\\system32"))
        assertFalse(BackupPaths.isSafeEntry("../evil"))
        assertFalse(BackupPaths.isSafeEntry("attachments/../../evil"))
        assertFalse(BackupPaths.isSafeEntry("attachments/..\\evil"))
        assertFalse(BackupPaths.isSafeEntry("attachments/c1/.."))
    }

    @Test
    fun recognisesAttachmentEntriesOnly() {
        assertTrue(BackupPaths.isAttachmentEntry("attachments/c1/a1.jpg"))
        assertTrue(BackupPaths.isAttachmentEntry("attachments/a"))
        assertFalse(BackupPaths.isAttachmentEntry("attachments/"))
        assertFalse(BackupPaths.isAttachmentEntry("attachments"))
        assertFalse(BackupPaths.isAttachmentEntry("messages.json"))
    }

    @Test
    fun rewritesConversationDirectoryInsideRawAttachmentJson() {
        val raw = """[{"id":"a1","path":"attachments/c1/a1.jpg","extractedPath":"attachments/c1/a1.extracted.txt"}]"""
        val rewritten = BackupPaths.rewriteConversationDir(raw, "c1", "c2")
        assertEquals(
            """[{"id":"a1","path":"attachments/c2/a1.jpg","extractedPath":"attachments/c2/a1.extracted.txt"}]""",
            rewritten,
        )
        // 原始 JSON 里出现过的其它内容一律不动。
        assertTrue(rewritten!!.contains("\"id\":\"a1\""))
    }

    @Test
    fun rewriteIsIdentityWhenNothingToDo() {
        val raw = """[{"id":"a1","path":"attachments/c1/a1.jpg"}]"""
        assertEquals(raw, BackupPaths.rewriteConversationDir(raw, "c1", "c1"))
        assertEquals(raw, BackupPaths.rewriteConversationDir(raw, "", "c9"))
        assertEquals(null, BackupPaths.rewriteConversationDir(null, "c1", "c2"))
    }

    @Test
    fun mapsRelativePathIntoNewConversationDirectory() {
        assertEquals(
            "attachments/c9/a1.jpg",
            BackupPaths.inConversationDir("attachments/c1/a1.jpg", "c9"),
        )
        assertEquals(
            "attachments/c9/a1.thumb.jpg",
            BackupPaths.inConversationDir("attachments/c1/a1.thumb.jpg", "c9"),
        )
    }
}

package com.zcw.chatai.data.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `FileSwap` 的核心承诺：**顶替失败时旧目录必须还是完整的**——
 * 导入是脱离界面生命周期跑的，被杀在「旧树已删、新树未到位」的窗口里会丢光附件。
 */
class FileSwapTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dirWith(parent: File, name: String, vararg files: String): File =
        File(parent, name).apply {
            mkdirs()
            files.forEach { File(this, it).writeText("content of $it") }
        }

    @Test
    fun replacesExistingTreeAndCleansUpIntermediateDirs() {
        val parent = temp.newFolder()
        val root = dirWith(parent, "attachments", "old.jpg")
        val staged = dirWith(parent, "staging", "new.jpg")

        assertTrue(FileSwap.swapIn(root, staged))

        assertTrue(File(root, "new.jpg").isFile)
        assertFalse("旧文件不该留下", File(root, "old.jpg").exists())
        assertFalse("中间目录该清掉", File(parent, "attachments.incoming").exists())
        assertFalse("中间目录该清掉", File(parent, "attachments.outgoing").exists())
        assertFalse("staged 已被搬走", staged.exists())
    }

    @Test
    fun movesTreeWhenRootDoesNotExistYet() {
        val parent = temp.newFolder()
        val root = File(parent, "attachments")
        val staged = dirWith(parent, "staging", "a.jpg")

        assertTrue(FileSwap.swapIn(root, staged))

        assertTrue(File(root, "a.jpg").isFile)
    }

    @Test
    fun failsAndLeavesRootUntouchedWhenNothingIsStaged() {
        val parent = temp.newFolder()
        val root = dirWith(parent, "attachments", "old.jpg")

        assertFalse(FileSwap.swapIn(root, File(parent, "staging")))

        assertTrue("旧树必须原样保留", File(root, "old.jpg").isFile)
    }

    @Test
    fun failsWhenStagedIsAFileRatherThanDirectory() {
        val parent = temp.newFolder()
        val root = dirWith(parent, "attachments", "old.jpg")
        val staged = File(parent, "staging").apply { writeText("not a directory") }

        assertFalse(FileSwap.swapIn(root, staged))

        assertTrue(File(root, "old.jpg").isFile)
    }

    /**
     * 顶替被杀死在「旧树已挪走、新树还没就位」这两个 rename 之间时，[root] 不存在，
     * 两份完整数据分别在 `.incoming`（新）与 `.outgoing`（旧）里。
     * 这时候**必须把新树放回原位**——直接删掉它们等于把附件全删了，而数据库还在引用。
     */
    @Test
    fun recoversNewTreeWhenInterruptedBetweenRenames() {
        val parent = temp.newFolder()
        val root = File(parent, "attachments")
        val incoming = dirWith(parent, "attachments.incoming", "new.jpg")
        val outgoing = dirWith(parent, "attachments.outgoing", "old.jpg")

        assertTrue(FileSwap.recoverAndClean(root))

        assertTrue("应当恢复成导入好的那棵树", File(root, "new.jpg").isFile)
        assertFalse(File(root, "old.jpg").exists())
        assertFalse(incoming.exists())
        assertFalse(outgoing.exists())
    }

    @Test
    fun recoversOldTreeWhenOnlyOutgoingSurvived() {
        val parent = temp.newFolder()
        val root = File(parent, "attachments")
        val outgoing = dirWith(parent, "attachments.outgoing", "old.jpg")

        assertTrue(FileSwap.recoverAndClean(root))

        assertTrue(File(root, "old.jpg").isFile)
        assertFalse(outgoing.exists())
    }

    /** 树还在就别动它，只清残留。 */
    @Test
    fun keepsLiveTreeAndCleansLeftovers() {
        val parent = temp.newFolder()
        val root = dirWith(parent, "attachments", "live.jpg")
        dirWith(parent, "attachments.incoming", "new.jpg")
        dirWith(parent, "attachments.outgoing", "old.jpg")

        assertTrue(FileSwap.recoverAndClean(root))

        assertTrue(File(root, "live.jpg").isFile)
        assertFalse(File(parent, "attachments.incoming").exists())
        assertFalse(File(parent, "attachments.outgoing").exists())
    }

    @Test
    fun nothingToRecoverWhenEverythingIsGone() {
        val parent = temp.newFolder()
        assertFalse(FileSwap.recoverAndClean(File(parent, "attachments")))
    }

    /** 恢复之后还能正常顶替（下一次导入不该被上一次的中断卡住）。 */
    @Test
    fun swapWorksAfterRecovery() {
        val parent = temp.newFolder()
        val root = File(parent, "attachments")
        dirWith(parent, "attachments.incoming", "recovered.jpg")
        assertTrue(FileSwap.recoverAndClean(root))

        val staged = dirWith(parent, "staging", "next.jpg")
        assertTrue(FileSwap.swapIn(root, staged))

        assertTrue(File(root, "next.jpg").isFile)
        assertFalse(File(root, "recovered.jpg").exists())
    }
}

package com.zcw.chatai.data.backup

import java.io.File

/**
 * 目录顶替（纯 `java.io`，JVM 单测覆盖）：用 [staged] 顶替 [root]，
 * **任何一步失败都不会破坏现有的 [root]**。
 *
 * 不能写成「先删 root、再把 staged 改名过去」：那样中间有一个「旧树已经删了、新树还没到位」的
 * 窗口，而这个导入是**刻意脱离界面生命周期**跑的（用户可以退出设置页、系统可以回收进程），
 * 进程在这时被杀就只剩一个空目录——唯一完整的那份随后还会被 `finally` 清掉。
 * 这里改成：先给新树占一个稳定名字，等它确实落到 [root] 之后再删旧树。
 */
object FileSwap {

    fun swapIn(root: File, staged: File): Boolean {
        if (!staged.isDirectory) return false
        val parent = root.parentFile ?: return false
        val incoming = incomingDir(root)
        val outgoing = outgoingDir(root)
        incoming.deleteRecursively()
        outgoing.deleteRecursively()
        // ① 新树先离开 staging（同一文件系统，rename 近乎零成本）。
        if (!staged.renameTo(incoming)) return false
        // ② 旧树挪走占位；不存在就直接进 ③。
        val hadRoot = root.exists()
        if (hadRoot && !root.renameTo(outgoing)) {
            incoming.renameTo(staged)
            return false
        }
        // ③ 新树就位；这一步失败就把一切放回原样。
        if (!incoming.renameTo(root)) {
            if (hadRoot) outgoing.renameTo(root)
            incoming.renameTo(staged)
            return false
        }
        outgoing.deleteRecursively()
        return true
    }

    /**
     * 冷启动的「先恢复、后清理」。顶替被进程杀死打断时，[root] 可能不存在，而两份完整数据
     * 分别躺在 `attachments.incoming`（新树）与 `attachments.outgoing`（旧树）里——
     * 这时**不能直接删它们**（那就是把附件全删了，而数据库还在引用），必须先把还能用的那棵放回原位。
     *
     * 优先用新树：覆盖式还原是「先换数据库、再换文件」，中断时数据库已经是导入后的一份。
     *
     * @return 是否落定了一棵完整的树（false 表示既没有 [root] 也没有可用的中间目录）。
     */
    fun recoverAndClean(root: File): Boolean {
        val incoming = incomingDir(root)
        val outgoing = outgoingDir(root)
        if (!root.exists()) {
            val survivor = when {
                incoming.isDirectory -> incoming
                outgoing.isDirectory -> outgoing
                else -> null
            } ?: return false
            root.parentFile?.mkdirs()
            // 放不回去就原样留着，下次启动再试——绝不能在这里把唯一的副本删掉。
            if (!survivor.renameTo(root)) return false
        }
        incoming.deleteRecursively()
        outgoing.deleteRecursively()
        return true
    }

    private fun incomingDir(root: File): File = File(root.parentFile, "${root.name}.incoming")

    private fun outgoingDir(root: File): File = File(root.parentFile, "${root.name}.outgoing")
}

package com.zcw.chatai.data.provider

/**
 * 模型名筛选（纯函数，JVM 可测）：忽略大小写、去掉查询串首尾空白，空查询返回全部。
 * 可见行数与懒加载由调用方的列表容器负责（设置页浮层最多 5 行、聊天弹层整块滚动）。
 */
object ModelFilter {

    fun filter(models: List<String>, query: String): List<String> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return models
        return models.filter { it.contains(keyword, ignoreCase = true) }
    }
}

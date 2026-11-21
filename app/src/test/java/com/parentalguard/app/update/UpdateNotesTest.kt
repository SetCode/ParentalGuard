package com.parentalguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** @brief 发布说明纯文本化：确认 Markdown 标记不会漏到对话框里 */
class UpdateNotesTest {

    private val raw = """
        ## 儿童设备管控 v1.1.0

        **变更内容（自 v1.0.1）**

        - 主界面分页：概览 / 规则 / 提示 / 设置
        - 界面细节优化：统一按钮体系

        下载 `ParentalGuard-v1.1.0.apk` 覆盖安装即可。

        校验：`sha256sum` 应与附件一致。
    """.trimIndent()

    @Test
    fun stripsMarkdownMarkers() {
        val plain = toPlainNotes(raw)
        assertFalse("不应残留标题符号", plain.contains("#"))
        assertFalse("不应残留加粗符号", plain.contains("**"))
        assertFalse("不应残留反引号", plain.contains("`"))
        assertTrue("列表项应转成圆点", plain.contains("· 主界面分页"))
        assertTrue(plain.contains("变更内容（自 v1.0.1）"))
        assertTrue(plain.contains("下载 ParentalGuard-v1.1.0.apk 覆盖安装即可。"))
    }

    @Test
    fun keepsTitleLineDropped() {
        // 对话框标题已显示版本号，正文里的标题行应整行丢弃
        assertFalse(toPlainNotes(raw).startsWith("儿童设备管控"))
    }

    @Test
    fun collapsesBlankLines() {
        val plain = toPlainNotes("第一段\n\n\n\n第二段")
        assertEquals("第一段\n\n第二段", plain)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals("", toPlainNotes(""))
        assertEquals("", toPlainNotes("   \n\n  "))
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("直接是纯文本\n第二行", toPlainNotes("直接是纯文本\n第二行"))
    }
}

package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 检查更新纯逻辑单测：releases 列表过滤（draft/prerelease/非 stable tag 剔除、
 * 日期取最大）与新旧比较（字典序）。org.json 走 Robolectric。
 * 网络层（3s 超时/失败分类）不在单测口径内。
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateCheckerTest {

    /** 模拟 GitHub releases 列表：正式稳定版 / assets 版权物(prerelease) / draft / 非 stable tag */
    private val sampleJson = """
    [
      {"tag_name": "stable-20261029", "draft": false, "prerelease": false,
       "assets": [{"name": "XQDK_20261029_43_armv8-release.apk"}]},
      {"tag_name": "assets", "draft": false, "prerelease": true},
      {"tag_name": "stable-20261028", "draft": false, "prerelease": false},
      {"tag_name": "stable-20261115", "draft": true, "prerelease": false},
      {"tag_name": "v3.1", "draft": false, "prerelease": false}
    ]
    """.trimIndent()

    @Test
    fun `过滤出正式 stable 版并保留全部候选`() {
        val rels = parseReleases(sampleJson)
        assertEquals(listOf("stable-20261029", "stable-20261028"), rels.map { it.tag })
    }

    @Test
    fun `取日期最大的为最新`() {
        val latest = parseReleases(sampleJson).maxByOrNull { it.date }
        assertEquals("stable-20261029", latest?.tag)
        assertEquals("20261029", latest?.date)
    }

    @Test
    fun `空列表与全过滤列表返回空`() {
        assertTrue(parseReleases("[]").isEmpty())
        // 只有 prerelease/draft/非 stable tag → 无正式版
        assertTrue(parseReleases("""[{"tag_name":"assets","prerelease":true}]""").isEmpty())
    }

    @Test
    fun `构建日期早于最新 tag 判新版`() {
        assertTrue(isNewer("20261029", "20261028"))
        // 同日=已最新（发布当天自查不误报）
        assertFalse(isNewer("20261029", "20261029"))
        // 构建日期更新（本地构建比最新发布新）=已最新
        assertFalse(isNewer("20261029", "20261030"))
    }

    @Test
    fun `8位日期字典序即时间序`() {
        // 跨年/跨月边界
        assertTrue(isNewer("20270101", "20261231"))
        assertFalse(isNewer("20261231", "20270101"))
    }
}

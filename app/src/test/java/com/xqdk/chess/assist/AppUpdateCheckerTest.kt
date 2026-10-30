package com.xqdk.chess.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 检查更新纯逻辑单测：releases 列表过滤（draft/prerelease/杂 tag 剔除）、双制式
 * tag 识别（新制 vX.Y[.Z] 取发布时间换算北京时间 / 旧制 stable-YYYYMMDD 取 tag
 * 名日期）与新旧比较（字典序）。org.json 走 Robolectric。
 * 网络层（3s 超时/失败分类）不在单测口径内。
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateCheckerTest {

    /** 模拟 GitHub releases 列表：新制 v 发布 / 旧制 stable / assets 版权物(prerelease) / draft / 杂 tag */
    private val sampleJson = """
    [
      {"tag_name": "v3.2", "draft": false, "prerelease": false,
       "published_at": "2026-10-31T07:05:00Z"},
      {"tag_name": "stable-20261029", "draft": false, "prerelease": false,
       "assets": [{"name": "XQDK_20261029_43_armv8-release.apk"}]},
      {"tag_name": "assets", "draft": false, "prerelease": true},
      {"tag_name": "stable-20261028", "draft": false, "prerelease": false},
      {"tag_name": "stable-20261115", "draft": true, "prerelease": false},
      {"tag_name": "release-candidate", "draft": false, "prerelease": false}
    ]
    """.trimIndent()

    @Test
    fun `双制式正式版全部保留且杂tag剔除`() {
        val rels = parseReleases(sampleJson)
        assertEquals(listOf("v3.2", "stable-20261029", "stable-20261028"), rels.map { it.tag })
    }

    @Test
    fun `新制v标签日期取发布时间并换算北京时间`() {
        // 07:05 UTC + 8h = 15:05 北京，仍是当日
        val rels = parseReleases(sampleJson)
        assertEquals("20261031", rels.first { it.tag == "v3.2" }.date)
    }

    @Test
    fun `取日期最大的为最新（新制晚于旧制时胜出）`() {
        val latest = parseReleases(sampleJson).maxByOrNull { it.date }
        assertEquals("v3.2", latest?.tag)
        assertEquals("20261031", latest?.date)
    }

    @Test
    fun `UTC深夜发布跨日到北京次日`() {
        // 16:05 UTC + 8h = 次日 00:05 北京——换算必须过时区，不能直接截取 UTC 日期
        val json = """[{"tag_name":"v3.2.1","draft":false,"prerelease":false,
                       "published_at":"2026-10-31T16:05:00Z"}]"""
        assertEquals("20261101", parseReleases(json).single().date)
    }

    @Test
    fun `v标签发布时间缺失回退创建时间且损坏则剔除`() {
        // published_at 损坏 → 回退 created_at
        val fallback = parseReleases(
            """[{"tag_name":"v3.1","draft":false,"prerelease":false,
                 "published_at":"not-a-date","created_at":"2026-10-30T02:00:00Z"}]""")
        assertEquals("20261030", fallback.single().date)
        // 两处时间都不可解析 → 该条剔除（定不了日期的发布不能参与"最新"比较）
        assertTrue(parseReleases(
            """[{"tag_name":"v3.1","draft":false,"prerelease":false,
                 "published_at":"x","created_at":"y"}]""").isEmpty())
    }

    @Test
    fun `空列表与全过滤列表返回空`() {
        assertTrue(parseReleases("[]").isEmpty())
        // 只有 prerelease/draft/杂 tag → 无正式版
        assertTrue(parseReleases("""[{"tag_name":"assets","prerelease":true}]""").isEmpty())
    }

    @Test
    fun `构建日期早于最新发布判新版`() {
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

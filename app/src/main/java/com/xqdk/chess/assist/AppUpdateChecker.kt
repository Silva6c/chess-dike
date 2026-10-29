package com.xqdk.chess.assist

import android.util.Log
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** stable tag 名约束（8 位日期；字典序 = 时间序） */
private val STABLE_TAG_RE = Regex("^stable-(\\d{8})$")

/** 单条 stable 发布（解析产物） */
data class StableRelease(val tag: String, val date: String)

/** 检查结果（sealed 便于 UI 侧穷尽处理） */
sealed class UpdateResult {
    /** 已是最新（或列表里没有比构建日期更新的正式版） */
    object UpToDate : UpdateResult()

    /** 发现新版本：tag = stable-YYYYMMDD，date = 8 位日期 */
    data class Newer(val tag: String, val date: String) : UpdateResult()

    /** 查询失败：短文案已归类，直接可上屏 */
    data class Failure(val message: String) : UpdateResult()
}

/**
 * 从 releases 列表 JSON 过滤出正式 stable-* 发布：排除 draft 与 prerelease
 * （assets 版权物 Release 应标 prerelease，此过滤是第二道保险），tag 名精确匹配
 * stable-YYYYMMDD。org.json 解析（Android 内置，JVM 单测走 Robolectric）。
 * 解析失败抛 org.json 异常，由调用方归类。
 */
fun parseReleases(json: String): List<StableRelease> {
    val arr = JSONArray(json)
    val out = ArrayList<StableRelease>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
        val tag = o.optString("tag_name")
        val m = STABLE_TAG_RE.find(tag) ?: continue
        out.add(StableRelease(tag, m.groupValues[1]))
    }
    return out
}

/** 构建日期早于最新 tag 日期即有新版（8 位日期字符串字典序比较）；同日=已最新 */
fun isNewer(latestDate: String, buildDate: String): Boolean = latestDate > buildDate

/**
 * 检查更新（连线页手动按钮，设计移植自 Cabinet internal/update）：
 * **不主动检查**——无启动自检、无后台轮询，用户点"检查更新"才发一次查询；
 * 查 GitHub releases 列表选最新正式 stable-*，与构建日期比较后提示，**不内置
 * 下载安装**（提示后跳 Release 页由用户手动下载——不申请 REQUEST_INSTALL_PACKAGES
 * 敏感权限，维持"最小权限集"的合规形象）。
 *
 * 设计要点（Cabinet 同款血泪经验）：
 * - **不用 releases/latest 端点**：assets 版权物 Release 也挂在仓库上，列表过滤
 *   + tag 日期取最大双保险（assets Release 应标 prerelease，过滤是第二道保险）；
 * - **版本比较用 tag 日期而非 versionCode**：公开仓"稳定版=一笔提交"会强推压平，
 *   提交数（versionCode）随压平回落，前后不可比；构建日期 vs tag 日期恒单调；
 * - **API 查询 3 秒超时**；失败归因为简短中文文案（中国大陆直连 api.github.com
 *   通常不可达，文案如实提示"需可访问 GitHub 的网络环境"），不猜代理不重试。
 *
 * 【线程】[fetchReleasesJson] 做网络 IO，调用方须在后台线程调用（连线页走
 * worker executor）；[parseReleases]/[isNewer] 纯函数，Robolectric 单测覆盖。
 */
class AppUpdateChecker(private val buildDate: String) {

    companion object {
        /** 本仓 releases 列表（匿名查询，免 token） */
        const val RELEASES_API = "https://api.github.com/repos/Silva6c/chess-dike/releases?per_page=10"
        /** 用户下载页（正式 Release，浏览器打开） */
        const val RELEASES_PAGE = "https://github.com/Silva6c/chess-dike/releases"
        /** API 查询超时（Cabinet 拍板定值 3s） */
        private const val TIMEOUT_MS = 3000
        private const val TAG = "AppUpdateChecker"

        /** 检查结果类型见文件顶层 [UpdateResult]（sealed，穷尽 when 用） */
    }

    /**
     * 拉取 releases 列表 JSON（网络 IO，后台线程调用）。
     * 失败抛 IOException（含超时/状态码非 200），由 [check] 归类为失败文案。
     */
    fun fetchReleasesJson(): String {
        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub API 强制 UA（缺省 UA 返回 403）
            conn.setRequestProperty("User-Agent", "XQDK/${com.xqdk.chess.BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub API 返回 $code")
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 完整检查流程：网络拉取 → 解析过滤 → 日期比较。后台线程调用。
     * 任何失败都归因为短文案（不抛出），完整错误链只进 logcat。
     */
    fun check(): UpdateResult {
        val rels = try {
            parseReleases(fetchReleasesJson())
        } catch (e: java.net.SocketTimeoutException) {
            Log.i(TAG, "check failed: timeout")
            return UpdateResult.Failure("网络超时，可稍后重试")
        } catch (e: IOException) {
            Log.i(TAG, "check failed", e)
            return UpdateResult.Failure(classify(e.message ?: ""))
        } catch (e: Throwable) {
            Log.i(TAG, "check failed", e)
            return UpdateResult.Failure("查询失败，请稍后重试")
        }
        val latest = rels.maxByOrNull { it.date }
        return if (latest == null) {
            // 列表无正式 stable-*：仓库刚建/全部为 prerelease 的边界，如实提示
            UpdateResult.Failure("暂无正式发布版本")
        } else if (isNewer(latest.date, buildDate)) {
            UpdateResult.Newer(latest.tag, latest.date)
        } else {
            UpdateResult.UpToDate
        }
    }

    /** 网络层错误归类为简短中文文案（照 Cabinet failureMessage 口径，上屏必须短） */
    private fun classify(detail: String): String {
        val s = detail.lowercase(Locale.US)
        return when {
            "timed out" in s || "timeout" in s -> "网络超时，可稍后重试"
            "403" in s -> "GitHub 匿名请求被限流，稍后重试"
            "404" in s -> "发布列表不可见（仓库未公开或暂无 Release）"
            else -> "网络不可达——api.github.com 需可访问 GitHub 的网络环境"
        }
    }
}

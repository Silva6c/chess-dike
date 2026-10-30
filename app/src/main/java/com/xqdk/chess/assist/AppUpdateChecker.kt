package com.xqdk.chess.assist

import android.util.Log
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 正式发布的 tag 双制式（2026-10-31 用户拍板切换）：
 * - 新制 `vX.Y` / `vX.Y.Z`（语义化版本，此后正式版只发这个）——tag 名里没有日期，
 *   日期取 Release 的发布时间（published_at，缺失回退 created_at）换算北京时间；
 * - 旧制 `stable-YYYYMMDD`（8 位日期，字典序 = 时间序）——已废弃停发，但历史
 *   stable Release 永久挂在仓库上，老用户的检查更新必须能继续看到它们。
 * 两制式的比较口径统一为 8 位北京时间日期（见 [ReleaseInfo]）。
 */
private val SEMVER_TAG_RE = Regex("^v\\d+(\\.\\d+){1,2}$")
private val STABLE_TAG_RE = Regex("^stable-(\\d{8})$")

/** 北京时区（Release 发布时间换算目标；CI 产物 BUILD_DATE 同为北京时间口径） */
private val ZONE_BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

/** 单条正式发布（解析产物）。date 统一为 8 位北京时间日期，两制式同口径比较 */
data class ReleaseInfo(val tag: String, val date: String)

/** GitHub Release 的 ISO8601 时间（如 2026-10-31T14:22:33Z）→ 北京时间 8 位日期；
 *  解析失败返回 null（调用方剔除该条——定不了日期的发布不能参与"最新"比较） */
internal fun isoToBeijingDate(iso: String): String? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZONE_BEIJING)
        .toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)
}.getOrNull()

/** 检查结果（sealed 便于 UI 侧穷尽处理） */
sealed class UpdateResult {
    /** 已是最新（或列表里没有比构建日期更新的正式版） */
    object UpToDate : UpdateResult()

    /** 发现新版本：tag = v3.2（新制）/ stable-20261030（旧制历史），date = 8 位日期 */
    data class Newer(val tag: String, val date: String) : UpdateResult()

    /** 查询失败：短文案已归类，直接可上屏 */
    data class Failure(val message: String) : UpdateResult()
}

/**
 * 从 releases 列表 JSON 过滤出正式发布：排除 draft 与 prerelease（assets 版权物
 * Release 应标 prerelease，此过滤是第二道保险），tag 双制式识别（见文件头）后
 * 统一换算 8 位日期。org.json 解析（Android 内置，JVM 单测走 Robolectric）。
 * 解析失败抛 org.json 异常，由调用方归类。
 */
fun parseReleases(json: String): List<ReleaseInfo> {
    val arr = JSONArray(json)
    val out = ArrayList<ReleaseInfo>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
        val tag = o.optString("tag_name")
        // 日期三源：旧制 tag 名自带 → 新制发布时间 → 缺失回退创建时间；全部不可得
        // （外来的杂 tag / 时间字段损坏）则剔除，绝不参与"最新"比较
        val date = STABLE_TAG_RE.find(tag)?.groupValues?.get(1)
            ?: (if (SEMVER_TAG_RE.matches(tag)) {
                isoToBeijingDate(o.optString("published_at"))
                    ?: isoToBeijingDate(o.optString("created_at"))
            } else null)
            ?: continue
        out.add(ReleaseInfo(tag, date))
    }
    return out
}

/** 构建日期早于最新发布日期即有新版（8 位日期字符串字典序比较）；同日=已最新 */
fun isNewer(latestDate: String, buildDate: String): Boolean = latestDate > buildDate

/**
 * 检查更新（连线页手动按钮，设计移植自 Cabinet internal/update）：
 * **不主动检查**——无启动自检、无后台轮询，用户点"检查更新"才发一次查询；
 * 查 GitHub releases 列表选最新正式发布（新制 vX.Y.Z + 旧制 stable-* 双制式并存），
 * 与构建日期比较后提示，**不内置下载安装**（提示后跳 Release 页由用户手动下载
 * ——不申请 REQUEST_INSTALL_PACKAGES 敏感权限，维持"最小权限集"的合规形象）。
 *
 * 设计要点（Cabinet 同款血泪经验）：
 * - **不用 releases/latest 端点**：assets 版权物 Release 也挂在仓库上，列表过滤
 *   + 发布日期取最大双保险（assets Release 应标 prerelease，过滤是第二道保险）；
 * - **版本比较用发布日期而非 versionCode**：公开仓"稳定版=一笔提交"会强推压平，
 *   提交数（versionCode）随压平回落、前后不可比；构建日期 vs 发布日期恒单调
 *   （新制日期取 Release 发布时间换算北京时间，旧制取 tag 名内嵌日期）；
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
            // 列表无正式发布（仓库刚建/全部为 prerelease 的边界），如实提示
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

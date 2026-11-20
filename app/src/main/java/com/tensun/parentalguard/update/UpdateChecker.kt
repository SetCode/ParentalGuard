package com.tensun.parentalguard.update

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** @brief 发布来源：与 CI 工作流、本地发布脚本共用的唯一仓库坐标 */
object ReleaseRepo {
    const val OWNER = "SetCode"
    const val NAME = "ParentalGuard"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$NAME/releases"
    const val LATEST_API = "https://api.github.com/repos/$OWNER/$NAME/releases/latest"
}

/** @brief 读取本机已安装包的版本信息，失败时降级为占位值避免检查流程崩溃 */
object AppVersion {

    /** @brief 已安装包的 versionName，用于与远端版本比较 */
    @Suppress("DEPRECATION")
    fun current(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    /** @brief 已安装包的 versionCode，用于界面展示与排查降级安装问题 */
    @Suppress("DEPRECATION")
    fun currentCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }
}

/** @brief 版本号解析与比较：只比较数字段，忽略 v 前缀与 -beta/+build 后缀 */
object VersionCompare {

    /**
     * @brief 解析版本号为数字段列表
     * @return 解析失败（空串、含非数字段、段数超过 4）返回 null，由调用方按格式异常处理
     */
    fun parse(raw: String?): List<Int>? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        s = s.substringBefore('-').substringBefore('+').trim()
        if (s.isEmpty()) return null
        val parts = s.split('.')
        if (parts.size > 4) return null
        val out = ArrayList<Int>(parts.size)
        for (p in parts) {
            // 每段最多 9 位，避免超长数字导致 toInt 溢出
            if (p.isEmpty() || p.length > 9 || !p.all { it.isDigit() }) return null
            out.add(p.toInt())
        }
        return out
    }

    /** @brief 把数字段还原为规范版本号字符串，段数保留原样 */
    fun format(parts: List<Int>): String = parts.joinToString(".")

    /**
     * @brief 比较两个版本号
     * @return a 小于 b 返回负数，相等返回 0，a 大于 b 返回正数；任一侧格式异常返回 null
     */
    fun compare(a: String?, b: String?): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val l = if (i < x.size) x[i] else 0
            val r = if (i < y.size) y[i] else 0
            if (l != r) return if (l < r) -1 else 1
        }
        return 0
    }
}

/** @brief 远端最新发布的可见信息，可直接喂给更新提示对话框 */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val publishedAt: String
)

/** @brief 检查结果：四类情况各自独立，界面据此给出可区分原因的提示 */
sealed class UpdateResult {

    /** @brief 发现可用新版本 */
    data class Available(val info: ReleaseInfo, val current: String) : UpdateResult()

    /** @brief 当前已是最新版本 */
    data class Latest(val current: String, val latest: String) : UpdateResult()

    /** @brief 仓库还没有发布任何正式版本（API 返回 404），不算错误 */
    data class NoRelease(val current: String) : UpdateResult()

    /** @brief 网络不可达、超时或服务端非 2xx */
    data class NetworkError(val detail: String) : UpdateResult()

    /** @brief 返回内容或版本号格式异常，无法安全比较 */
    data class FormatError(val detail: String) : UpdateResult()
}

/** @brief 更新检查的本地记录：用于限制启动时自动检查的频率 */
object UpdatePrefs {

    private const val FILE = "pg_update"
    private const val KEY_LAST_CHECK = "lastCheckMs"

    /** @brief 上次检查时间戳，0 表示从未检查 */
    fun lastCheckMs(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)

    /** @brief 记录本次检查时间 */
    fun markChecked(context: Context, now: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, now).apply()
    }
}

/**
 * @brief GitHub Releases 更新检查器
 *
 * 通过公开 API 读取 latest Release，与已安装版本比较：
 * 有新版返回下载地址，无新版与「仓库尚未发布」区分开，网络与格式异常单独归类。
 * 所有网络调用都是阻塞的，必须由调用方放到后台线程（或使用 [checkAsync]）。
 */
object UpdateChecker {

    private const val CONNECT_TIMEOUT = 6_000
    private const val READ_TIMEOUT = 8_000

    /** @brief 发布说明在对话框中最多展示的字数，超出截断避免撑爆界面 */
    private const val MAX_NOTES = 1_200

    /** @brief 同步检查，只能在后台线程调用 */
    fun check(context: Context): UpdateResult {
        val current = AppVersion.current(context)
        // 下面所有 catch 分支都会直接返回，因此这里可以依赖「必定赋值」
        var httpCode: Int
        var body: String
        try {
            val conn = (URL(ReleaseRepo.LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                requestMethod = "GET"
                doInput = true
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub API 要求带 User-Agent，否则直接 403
                setRequestProperty("User-Agent", "ParentalGuard/$current")
            }
            try {
                httpCode = conn.responseCode
                val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
                body = stream?.use { s ->
                    BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
                }.orEmpty()
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            return UpdateResult.NetworkError("连接 GitHub 超时，请检查网络后重试")
        } catch (e: IOException) {
            return UpdateResult.NetworkError("无法连接 GitHub：${e.message ?: "网络不可用"}")
        } catch (e: SecurityException) {
            return UpdateResult.NetworkError("缺少联网权限，无法检查更新")
        }

        if (httpCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return UpdateResult.NoRelease(current)
        }
        if (httpCode !in 200..299) {
            return UpdateResult.NetworkError("GitHub 返回 HTTP $httpCode，请稍后重试")
        }

        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return UpdateResult.FormatError("发布信息不是合法 JSON，可能被网络代理改写")
        }

        val tag = json.optString("tag_name").trim()
        val parsed = VersionCompare.parse(tag)
            ?: return UpdateResult.FormatError(
                if (tag.isEmpty()) "最新发布缺少版本标签 tag_name" else "最新发布的标签「$tag」不是合法版本号"
            )
        val latest = VersionCompare.format(parsed)

        val cmp = VersionCompare.compare(latest, current)
            ?: return UpdateResult.FormatError("本机版本号「$current」格式异常，无法与 v$latest 比较")

        if (cmp <= 0) return UpdateResult.Latest(current, latest)

        val notes = json.optString("body").trim().let {
            if (it.length > MAX_NOTES) it.take(MAX_NOTES) + "\n…（完整说明见发布页）" else it
        }
        val pageUrl = json.optString("html_url").trim().ifEmpty { ReleaseRepo.RELEASES_PAGE }
        val title = json.optString("name").trim().ifEmpty { "v$latest" }
        val publishedAt = json.optString("published_at").trim()
        val apkUrl = pickApkUrl(json.optJSONArray("assets"), latest, pageUrl)

        return UpdateResult.Available(
            ReleaseInfo(
                version = latest,
                tag = tag,
                title = title,
                notes = notes,
                pageUrl = pageUrl,
                apkUrl = apkUrl,
                publishedAt = publishedAt
            ),
            current = current
        )
    }

    /** @brief 异步检查：后台线程请求，结果回主线程；回调前不会抛异常 */
    fun checkAsync(context: Context, onDone: (UpdateResult) -> Unit) {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                check(app)
            } catch (e: Exception) {
                UpdateResult.NetworkError("检查更新失败：${e.message ?: "未知错误"}")
            }
            handler.post { onDone(result) }
        }.start()
    }

    /**
     * @brief 从 Release 附件里挑安装包
     *
     * 优先选文件名含本版本号的 .apk，其次任意 .apk，都没有则退回发布页地址，
     * 保证「下载」入口永远有可用链接。
     */
    private fun pickApkUrl(assets: JSONArray?, version: String, fallback: String): String {
        if (assets == null || assets.length() == 0) return fallback
        var anyApk = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            val url = a.optString("browser_download_url").trim()
            if (url.isEmpty()) continue
            if (anyApk.isEmpty()) anyApk = url
            if (name.contains(version.lowercase())) return url
        }
        return if (anyApk.isNotEmpty()) anyApk else fallback
    }
}

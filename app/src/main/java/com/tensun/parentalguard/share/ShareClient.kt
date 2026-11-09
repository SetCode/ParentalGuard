package com.tensun.parentalguard.share

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/** @brief 自动发现到的局域网服务器 */
data class DiscoveredServer(val ip: String, val port: Int, val deviceId: String, val name: String) {
    val url: String get() = "http://$ip:$port"
}

/** @brief 同步结果：成功时携带服务端返回的全量数据集与冲突明细 */
data class SyncResult(
    val ok: Boolean,
    val state: LinkState,
    val message: String,
    val detail: String,
    val serverState: SharedState? = null,
    val conflicts: List<String> = emptyList()
)

/**
 * @brief 局域网客户端：连通性探测、全量同步、UDP 自动发现
 *
 * 所有网络操作都必须在后台线程执行；错误按「服务器未启动 / 地址不可达 /
 * 连接中断 / 同步失败（含数据校验）」明确分类，供界面给出可区分原因的提示。
 */
object ShareClient {

    private const val CONNECT_TIMEOUT = 4_000
    private const val READ_TIMEOUT = 6_000

    /** @brief 规范化服务器地址，支持直接输入 IP 或 http://IP:端口 */
    fun normalize(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    private fun request(base: String, path: String, method: String, body: String?): Pair<Int, String> {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = method
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun failure(e: Exception): SyncResult {
        val wasConnected = ShareStore.everConnected
        return when (e) {
            is ConnectException -> SyncResult(
                false, if (wasConnected) LinkState.DISCONNECTED else LinkState.UNREACHABLE,
                if (wasConnected) "与服务器的连接已中断" else "服务器地址不可达",
                "连接被拒绝：目标设备可能未启动本地服务器，或 IP / 端口不正确"
            )
            is SocketTimeoutException -> SyncResult(
                false, if (wasConnected) LinkState.DISCONNECTED else LinkState.UNREACHABLE,
                if (wasConnected) "连接超时，可能已离开该局域网" else "连接服务器超时",
                "在 ${CONNECT_TIMEOUT / 1000} 秒内没有收到响应，请确认两台设备在同一 Wi-Fi"
            )
            is java.net.UnknownHostException -> SyncResult(
                false, LinkState.UNREACHABLE, "服务器地址无法解析", "请检查输入的 IP 地址格式"
            )
            else -> SyncResult(
                false, LinkState.SYNC_FAILED, "同步失败",
                "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
            )
        }
    }

    /** @brief 连通性探测，不改动任何数据 */
    fun probe(base: String): SyncResult = try {
        val (code, text) = request(normalize(base), "/api/ping", "GET", null)
        if (code !in 200..299) {
            SyncResult(false, LinkState.SYNC_FAILED, "服务器返回错误", "HTTP $code：$text")
        } else {
            val o = JSONObject(text)
            if (!o.optBoolean("ok", false)) {
                SyncResult(false, LinkState.SYNC_FAILED, "服务器响应异常", "ping 未返回有效标识")
            } else {
                ShareStore.everConnected = true
                ShareStore.lastSyncAtMs = System.currentTimeMillis()
                SyncResult(true, LinkState.CONNECTED, "已连接局域网服务器", o.optString("deviceName", "未知设备"))
            }
        }
    } catch (e: Exception) {
        failure(e)
    }

    /**
     * @brief 与服务器做一次全量同步：提交本地数据集 -> 服务端串行合并 -> 应用返回结果
     *
     * 服务端合并结果就是权威数据集，客户端直接替换，保证多设备最终收敛到同一份数据。
     */
    fun sync(base: String): SyncResult {
        return try {
            val url = normalize(base)
            val local = ShareStore.snapshotState()
            val payload = JSONObject()
                .put("baseRevision", local.revision)
                .put("state", SharedState.envelope(local))
                .toString()
            val (code, text) = request(url, "/api/sync", "POST", payload)
            if (code !in 200..299) {
                SyncResult(false, LinkState.SYNC_FAILED, "同步失败", "HTTP $code：$text")
            } else {
                val o = JSONObject(text)
                if (!o.optBoolean("ok", false)) {
                    SyncResult(false, LinkState.SYNC_FAILED, "同步失败", o.optString("error", "服务端拒绝数据"))
                } else {
                    val remote = o.optJSONObject("state")?.let { SharedState.fromJson(it) }
                    if (remote == null) {
                        SyncResult(false, LinkState.SYNC_FAILED, "同步失败", "服务端返回的数据集校验未通过")
                    } else {
                        val a = o.optJSONArray("conflicts") ?: JSONArray()
                        val conflicts = ArrayList<String>()
                        for (i in 0 until a.length()) conflicts.add(a.optString(i, ""))
                        ShareStore.everConnected = true
                        ShareStore.lastSyncAtMs = System.currentTimeMillis()
                        SyncResult(
                            true,
                            if (conflicts.isNotEmpty()) LinkState.CONFLICT_MERGED else LinkState.CONNECTED,
                            if (conflicts.isNotEmpty()) "检测到并发修改，已自动合并" else "同步成功",
                            if (conflicts.isNotEmpty()) conflicts.joinToString("；")
                            else "数据集版本 ${o.optLong("revision", 0L)}",
                            remote, conflicts
                        )
                    }
                }
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    /** @brief UDP 广播发现同一局域网内已启动服务器的设备 */
    fun discover(timeoutMs: Int = 1_600): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        val socket = try {
            DatagramSocket().apply { broadcast = true; soTimeout = 350 }
        } catch (e: Exception) {
            return emptyList()
        }
        try {
            val payload = DISCOVERY_REQUEST.toByteArray(StandardCharsets.UTF_8)
            val targets = HashSet<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            for (addr in subnetBroadcasts()) targets.add(addr)
            for (t in targets) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, t, DISCOVERY_PORT))
                } catch (e: Exception) { /* 某个目标不可达时继续尝试其他目标 */ }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val text = String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8)
                    if (!text.startsWith(DISCOVERY_REPLY_PREFIX)) continue
                    val p = text.removePrefix(DISCOVERY_REPLY_PREFIX).split("|", limit = 3)
                    if (p.size < 3) continue
                    val port = p[0].toIntOrNull() ?: continue
                    val ip = pkt.address?.hostAddress.orEmpty()
                    if (ip.isEmpty()) continue
                    val server = DiscoveredServer(ip, port, p[1], p[2])
                    found[ip] = server
                } catch (e: SocketTimeoutException) {
                    // 继续等待，直到总超时
                } catch (e: Exception) {
                    break
                }
            }
        } finally {
            try { socket.close() } catch (e: Exception) { /* 已关闭 */ }
        }
        return found.values.toList()
    }

    private fun subnetBroadcasts(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (iaddr in ni.interfaceAddresses) {
                    val b = iaddr.broadcast ?: continue
                    if (b.address?.size == 4) list.add(b)
                }
            }
        } catch (e: Exception) {
            // 忽略，至少还有 255.255.255.255
        }
        return list
    }
}

package com.tensun.parentalguard.share

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** @brief UDP 自动发现端口与口令（仅局域网广播，不访问任何云端服务） */
const val DISCOVERY_PORT = 8766
const val DISCOVERY_REQUEST = "PG_DISCOVER_V1"
const val DISCOVERY_REPLY_PREFIX = "PG_SERVER_V1|"

/**
 * @brief 应用内置的极简 HTTP/1.1 局域网服务器（纯 java.net，无第三方依赖）
 *
 * 服务端把全部写操作交给 ShareStore 的同一把锁串行执行；客户端提交时携带
 * baseRevision，服务端检测到并发写入时按确定性规则自动合并并返回冲突明细。
 * 同时监听 UDP 广播，让同一局域网内安装了本应用的设备可以自动发现服务器。
 */
object ShareServer {

    private const val TAG = "ShareServer"

    private val runningFlag = AtomicBoolean(false)

    @Volatile
    private var acceptSocket: ServerSocket? = null

    @Volatile
    private var discoverySocket: DatagramSocket? = null

    private var acceptThread: Thread? = null
    private var discoveryThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(4)

    @Volatile
    var boundPort: Int = 0
        private set

    fun isRunning(): Boolean = runningFlag.get()

    /** @brief 启动本地服务器；返回是否成功，失败原因写入 ShareStore.status */
    fun start(port: Int): Boolean {
        if (runningFlag.get()) return true
        val p = port.coerceIn(1024, 65535)
        val server = try {
            ServerSocket(p, 32, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            Log.w(TAG, "端口绑定失败: ${e.message}")
            ShareStore.publishStatus(
                ShareStatus(
                    LinkState.SYNC_FAILED, "本地服务器启动失败",
                    "端口 $p 被占用或不可用：${e.javaClass.simpleName}",
                    ShareStore.lastSyncAtMs, localIpAddress(), ShareStore.settings.peerUrl
                )
            )
            return false
        }
        acceptSocket = server
        boundPort = server.localPort
        runningFlag.set(true)
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(4)

        acceptThread = Thread({
            while (runningFlag.get()) {
                try {
                    val client = server.accept()
                    if (!runningFlag.get()) {
                        client.close()
                        continue
                    }
                    pool.execute { handle(client) }
                } catch (e: SocketException) {
                    if (runningFlag.get()) Log.w(TAG, "accept 异常: ${e.message}")
                } catch (e: Exception) {
                    if (runningFlag.get()) Log.w(TAG, "accept 异常: ${e.message}")
                }
            }
        }, "pg-http-accept").also { it.isDaemon = true; it.start() }

        startDiscovery()
        return true
    }

    /** @brief 停止本地服务器并释放端口 */
    fun stop() {
        if (!runningFlag.get()) return
        runningFlag.set(false)
        try { acceptSocket?.close() } catch (e: Exception) { /* 已关闭 */ }
        try { discoverySocket?.close() } catch (e: Exception) { /* 已关闭 */ }
        acceptSocket = null
        discoverySocket = null
        acceptThread?.interrupt()
        discoveryThread?.interrupt()
        pool.shutdownNow()
        pool = Executors.newFixedThreadPool(4)
        boundPort = 0
    }

    private fun startDiscovery() {
        val ds = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.w(TAG, "发现服务不可用: ${e.message}")
            return
        }
        discoverySocket = ds
        discoveryThread = Thread({
            val buf = ByteArray(256)
            while (runningFlag.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    ds.receive(pkt)
                    val text = String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8)
                    if (text != DISCOVERY_REQUEST) continue
                    val reply = (DISCOVERY_REPLY_PREFIX + boundPort + "|" +
                            ShareStore.deviceId + "|" + ShareStore.settings.deviceName)
                        .toByteArray(StandardCharsets.UTF_8)
                    ds.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                } catch (e: java.net.SocketTimeoutException) {
                    // 超时用于让线程及时响应 stop()
                } catch (e: Exception) {
                    if (runningFlag.get()) Log.w(TAG, "发现广播异常: ${e.message}")
                }
            }
        }, "pg-udp-discovery").also { it.isDaemon = true; it.start() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 8_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", true)) {
                    contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
            contentLength = contentLength.coerceIn(0, 512 * 1024)
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(bytes, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read, StandardCharsets.UTF_8)
            } else ""

            val response = route(method, path, body)
            writeResponse(socket, response.first, response.second.toString())
        } catch (e: Exception) {
            Log.w(TAG, "请求处理异常: ${e.message}")
            try {
                writeResponse(socket, 500, JSONObject().put("ok", false).put("error", "服务端内部异常").toString())
            } catch (ignored: Exception) { /* 连接已断开 */ }
        } finally {
            try { socket.close() } catch (e: Exception) { /* 已关闭 */ }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun writeResponse(socket: Socket, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        val head = ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
        out.write(head)
        out.write(bytes)
        out.flush()
    }

    private fun route(method: String, path: String, body: String): Pair<Int, JSONObject> {
        return try {
            when {
                path == "/api/ping" && method == "GET" -> 200 to JSONObject()
                    .put("ok", true)
                    .put("deviceId", ShareStore.deviceId)
                    .put("deviceName", ShareStore.settings.deviceName)
                    .put("revision", ShareStore.revision())
                    .put("port", boundPort)

                path == "/api/state" && method == "GET" ->
                    200 to SharedState.envelope(ShareStore.snapshotState())

                path == "/api/sync" && method == "POST" -> handleSync(body)

                path.startsWith("/api/subjects/") && method == "PUT" ->
                    handleSubjectPut(path.removePrefix("/api/subjects/"), body)

                path.startsWith("/api/subjects/") && method == "DELETE" ->
                    handleSubjectDelete(path.removePrefix("/api/subjects/"))

                path.startsWith("/api/") -> 404 to JSONObject().put("ok", false).put("error", "接口不存在")
                else -> 404 to JSONObject().put("ok", false).put("error", "路径不存在")
            }
        } catch (e: org.json.JSONException) {
            400 to JSONObject().put("ok", false).put("error", "JSON 数据格式错误：${e.message}")
        } catch (e: Exception) {
            500 to JSONObject().put("ok", false).put("error", "服务端处理失败：${e.message}")
        }
    }

    private fun handleSync(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val base = o.optLong("baseRevision", -1L)
        val remoteObj = o.optJSONObject("state")
            ?: return 400 to JSONObject().put("ok", false).put("error", "缺少 state 数据")
        val remote = SharedState.fromJson(remoteObj)
            ?: return 400 to JSONObject().put("ok", false).put("error", "state 数据校验失败")
        val result = ShareStore.mergeRemote(remote, base)
        return 200 to JSONObject()
            .put("ok", true)
            .put("state", SharedState.envelope(result.state))
            .put("revision", result.revision)
            .put("conflicts", org.json.JSONArray().also { a -> result.conflicts.forEach { a.put(it) } })
            .put("merged", result.conflicts.isNotEmpty())
    }

    private fun handleSubjectPut(id: String, body: String): Pair<Int, JSONObject> {
        if (id.isEmpty() || id.length > 128) {
            return 400 to JSONObject().put("ok", false).put("error", "对象 ID 不合法")
        }
        val o = JSONObject(body)
        val record = SubjectRecord.fromJson(o.optJSONObject("subject") ?: o)
            ?: return 400 to JSONObject().put("ok", false).put("error", "对象数据校验失败")
        if (record.id != id) {
            return 400 to JSONObject().put("ok", false).put("error", "对象 ID 与路径不一致")
        }
        val wrapper = SharedState(subjects = LinkedHashMap<String, SubjectRecord>().apply { put(id, record) })
        val result = ShareStore.mergeRemote(wrapper, o.optLong("baseRevision", -1L))
        return 200 to JSONObject().put("ok", true).put("revision", result.revision)
    }

    private fun handleSubjectDelete(id: String): Pair<Int, JSONObject> {
        if (id.isEmpty() || id.length > 128) {
            return 400 to JSONObject().put("ok", false).put("error", "对象 ID 不合法")
        }
        val ok = ShareStore.deleteSubject(id)
        return if (ok) {
            200 to JSONObject().put("ok", true)
        } else {
            400 to JSONObject().put("ok", false).put("error", "对象不存在或不允许删除")
        }
    }

    /** @brief 取本机在局域网中的 IPv4 地址，供界面显示与手动连接 */
    fun localIpAddress(): String {
        return try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a.address?.size == 4 && !a.isLinkLocalAddress) {
                        return a.hostAddress ?: continue
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }
}

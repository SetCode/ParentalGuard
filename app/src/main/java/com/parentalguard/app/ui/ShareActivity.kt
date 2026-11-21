package com.parentalguard.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.parentalguard.app.R
import com.parentalguard.app.core.Formatter
import com.parentalguard.app.share.ChangeRecord
import com.parentalguard.app.share.ShareClient
import com.parentalguard.app.share.LinkState
import com.parentalguard.app.share.ShareServer
import com.parentalguard.app.share.ShareStatus
import com.parentalguard.app.share.ShareStore
import com.parentalguard.app.share.ShareSync
import com.parentalguard.app.share.SubjectRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @brief 局域网共享设置页
 *
 * 提供：本机服务器开关、连接/自动发现、按原因区分的连接状态、
 * 多个监控对象的创建 / 切换 / 重命名 / 删除与各自的更新记录。
 */
class ShareActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var swServer: Switch
    private lateinit var etPort: EditText
    private lateinit var tvServerName: TextView
    private lateinit var tvLocalAddress: TextView
    private lateinit var etPeerUrl: EditText
    private lateinit var tvDiscovered: TextView
    private lateinit var etNewSubject: EditText
    private lateinit var subjectContainer: LinearLayout
    private lateinit var tvHistory: TextView

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var refreshing = false

    private var lastListRenderMs = 0L
    private var lastSubjectsFingerprint = ""

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShareSync.init(applicationContext)
        setContentView(R.layout.activity_share)
        title = "局域网本地共享"
        bindViews()
        setupListeners()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvShareStatus)
        tvDetail = findViewById(R.id.tvShareDetail)
        tvLastSync = findViewById(R.id.tvLastSync)
        swServer = findViewById(R.id.swServer)
        etPort = findViewById(R.id.etPort)
        tvServerName = findViewById(R.id.tvServerName)
        tvLocalAddress = findViewById(R.id.tvLocalAddress)
        etPeerUrl = findViewById(R.id.etPeerUrl)
        tvDiscovered = findViewById(R.id.tvDiscovered)
        etNewSubject = findViewById(R.id.etNewSubject)
        subjectContainer = findViewById(R.id.subjectContainer)
        tvHistory = findViewById(R.id.tvHistory)

        val s = ShareStore.settings
        swServer.isChecked = s.serverEnabled
        etPort.setText(s.port.toString())
        etPeerUrl.setText(s.peerUrl)
    }

    private fun setupListeners() {
        swServer.setOnCheckedChangeListener { _, checked ->
            val port = etPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
            val ok = ShareSync.setServerEnabled(checked, port)
            if (!ok) {
                swServer.isChecked = false
                toast("本地服务器启动失败，请更换端口后重试")
            } else {
                ShareStore.addEvent("server", if (checked) "启动本机服务器（端口 $port）" else "停止本机服务器")
            }
            renderAll()
        }

        findViewById<Button>(R.id.btnAddSubject).setOnClickListener {
            val name = etNewSubject.text.toString().trim()
            if (name.isEmpty()) {
                toast("请输入监控对象名称")
                return@setOnClickListener
            }
            val r = ShareStore.createSubject(name)
            if (r == null) {
                toast("创建失败：名称为空或对象数量已达上限")
            } else {
                etNewSubject.setText("")
                toast("已创建「${r.name}」，点击「切换」开始使用")
                ShareSync.afterSubjectChanged()
                renderAll()
            }
        }

        findViewById<Button>(R.id.btnDiscover).setOnClickListener {
            tvDiscovered.text = "正在搜索同一局域网内的服务器…"
            ShareSync.discover { list ->
                if (list.isEmpty()) {
                    tvDiscovered.text = "未发现服务器：请确认对方已开启本机服务器且两台设备在同一 Wi-Fi"
                } else {
                    tvDiscovered.text = "发现 ${list.size} 台服务器，点击选择"
                    val labels = list.map { "${it.name}  ${it.url}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("选择要连接的服务器")
                        .setItems(labels) { _, which ->
                            etPeerUrl.setText(list[which].url)
                            connect()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connect() }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            ShareSync.disconnect()
            ShareStore.addEvent("disconnect", "断开共享连接")
            toast("已断开，数据仍保存在本机")
            renderAll()
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            if (etPeerUrl.text.toString().trim().isEmpty()) {
                toast("请先填写或发现服务器地址")
            } else {
                ShareStore.updateSettings { it.peerUrl = ShareClient.normalize(etPeerUrl.text.toString()) }
                ShareSync.requestSync(true)
                toast("已发起同步")
            }
        }

        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            val url = etPeerUrl.text.toString().trim()
            tvStatus.text = "正在测试连通性…"
            ShareSync.probe(url) { r ->
                toast("${r.message}｜${r.detail}")
                renderAll()
            }
        }
    }

    private fun connect() {
        val url = etPeerUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("请输入服务器地址")
            return
        }
        tvStatus.text = "正在连接服务器…"
        Thread {
            val r = ShareSync.connect(url)
            runOnUiThread {
                toast("${r.message}｜${r.detail}")
                ShareStore.addEvent("connect", "${r.message}｜${r.detail}")
                renderAll()
            }
        }.start()
    }

    private fun renderAll() {
        renderStatus()
        renderServer()
        renderSubjects()
        renderHistory()
    }

    private fun renderStatus() {
        val s: ShareStatus = ShareStore.status
        tvStatus.text = s.message
        tvDetail.text = s.detail
        val color = when (s.state) {
            LinkState.CONNECTED, LinkState.SERVER_RUNNING -> R.color.success
            LinkState.CONFLICT_MERGED -> R.color.warning
            LinkState.IDLE -> R.color.text_sub
            LinkState.SERVER_NOT_STARTED, LinkState.UNREACHABLE,
            LinkState.DISCONNECTED, LinkState.SYNC_FAILED -> R.color.danger
        }
        tvStatus.setTextColor(getColor(color))
        tvLastSync.text = if (s.lastSyncAtMs > 0L) {
            "最近同步：${timeFmt.format(Date(s.lastSyncAtMs))}"
        } else {
            "最近同步：尚未同步"
        }
    }

    private fun renderServer() {
        val s = ShareStore.settings
        tvServerName.text = "服务器名称：${ShareStore.deviceName()}（取自设备型号）"
        tvLocalAddress.text = if (ShareServer.isRunning()) {
            "本机地址：http://${ShareServer.localIpAddress()}:${s.port}（其他设备填写此地址）"
        } else {
            "本机地址：未启动"
        }
    }

    private fun renderSubjects() {
        val list = ShareStore.subjects()
        val fingerprint = list.joinToString("|") {
            "${it.id}:${it.name}:${it.timing.globalTotalMs()}:${it.history.size}:${it.deleted}"
        }
        val now = System.currentTimeMillis()
        if (fingerprint == lastSubjectsFingerprint && now - lastListRenderMs < 3_000L) return
        lastSubjectsFingerprint = fingerprint
        lastListRenderMs = now

        val active = ShareStore.activeId()
        subjectContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (r in list) {
            val v = inflater.inflate(R.layout.item_subject, subjectContainer, false)
            val isActive = r.id == active
            v.findViewById<TextView>(R.id.tvSubjectName).text = r.name
            v.findViewById<TextView>(R.id.tvSubjectActive).text = if (isActive) "当前" else ""
            val info = "累计 ${Formatter.human(r.timing.globalTotalMs())}" +
                    "｜最近更新 ${if (r.timing.latestUpdateMs() > 0) timeFmt.format(Date(r.timing.latestUpdateMs())) else "—"}" +
                    "｜记录 ${r.history.size} 条" +
                    if (r.timing.anyTimingActive()) "｜正在计时" else "｜已暂停"
            v.findViewById<TextView>(R.id.tvSubjectInfo).text = info

            val btnSwitch = v.findViewById<Button>(R.id.btnSwitch)
            btnSwitch.isEnabled = !isActive
            btnSwitch.setOnClickListener {
                if (ShareStore.switchSubject(r.id)) {
                    ShareSync.afterSubjectChanged()
                    toast("已切换到「${r.name}」，配置与计时数据独立")
                    renderAll()
                }
            }

            v.findViewById<Button>(R.id.btnRename).setOnClickListener { showRenameDialog(r) }

            val btnDelete = v.findViewById<Button>(R.id.btnDelete)
            btnDelete.isEnabled = r.id != "default"
            btnDelete.setOnClickListener { showDeleteDialog(r) }
            subjectContainer.addView(v)
        }
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = "暂无监控对象"
            empty.setTextColor(getColor(R.color.text_sub))
            subjectContainer.addView(empty)
        }
    }

    private fun showRenameDialog(r: SubjectRecord) {
        val input = EditText(this)
        input.setText(r.name)
        input.setSelection(r.name.length)
        AlertDialog.Builder(this)
            .setTitle("重命名监控对象")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (ShareStore.renameSubject(r.id, name)) {
                    ShareSync.afterSubjectChanged()
                    renderAll()
                } else {
                    toast("名称无效或未发生变化")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(r: SubjectRecord) {
        AlertDialog.Builder(this)
            .setTitle("删除监控对象")
            .setMessage("确定删除「${r.name}」？该对象的配置、计时与更新记录将在本机及其他设备上一并移除，其他对象不受影响。")
            .setPositiveButton("删除") { _, _ ->
                if (ShareStore.deleteSubject(r.id)) {
                    ShareSync.afterSubjectChanged()
                    toast("已删除「${r.name}」")
                    renderAll()
                } else {
                    toast("默认对象不能删除")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderHistory() {
        val r = ShareStore.subject(ShareStore.activeId()) ?: run {
            tvHistory.text = "暂无记录"
            return
        }
        val records: List<ChangeRecord> = r.history.sortedByDescending { it.atMs }.take(12)
        if (records.isEmpty()) {
            tvHistory.text = "暂无记录"
            return
        }
        tvHistory.text = records.joinToString("\n") {
            "• ${timeFmt.format(Date(it.atMs))}  ${it.deviceName}  [${actionLabel(it.action)}]  ${it.detail}"
        }
    }

    private fun actionLabel(a: String): String = when (a) {
        "create" -> "创建"
        "rename" -> "重命名"
        "switch" -> "切换"
        "delete" -> "删除"
        "config" -> "配置"
        "timing" -> "计时"
        "sync" -> "同步"
        "sync_failed" -> "同步失败"
        "conflict" -> "冲突合并"
        "connect" -> "连接"
        "disconnect" -> "断开"
        "server" -> "服务器"
        "reset_time" -> "时间重置"
        else -> a
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshing) return
            try {
                renderStatus()
                renderServer()
                renderSubjects()
                renderHistory()
            } catch (e: Exception) {
                // 单次刷新异常不影响下一次
            }
            handler.postDelayed(this, 1_000L)
        }
    }
}

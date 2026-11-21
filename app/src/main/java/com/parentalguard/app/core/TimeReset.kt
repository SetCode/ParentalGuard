package com.parentalguard.app.core

import android.content.Context
import com.parentalguard.app.data.UsageRepository
import com.parentalguard.app.service.GuardService
import com.parentalguard.app.share.ShareStore
import com.parentalguard.app.share.ShareSync

/**
 * @brief 时间重置编排器：把所有相关时间值一次性恢复到项目预设的初始状态
 *
 * 重置范围严格限定为「当前激活监控对象的计时数据」：
 * - 本地 pg_usage 分区：今日总时长、各应用累计、单次连续时长、强制休息截止、
 *   进行中 / 暂停的计时片段、熄屏锚点与全部提醒标志；
 * - 局域网共享数据集：当前对象在各台设备上的计时分量，并推进重置纪元，
 *   使旧纪元数据在后续合并中被丢弃，重置结果不会被其他设备拉回；
 * - 进程运行态：休息进度、锁定 / 拦截 / 提示去重、通知缓存与全屏遮罩；
 * - 界面与通知：状态总线立即失效，服务即时心跳重算并刷新下拉通知。
 *
 * 不修改：管控规则与时长上限、白名单、单应用限额、家长密码、提示方式、
 * 监控对象列表、局域网连接设置、更新记录以外的任何配置或业务数据。
 * 每一步独立容错：共享层不可用或服务未运行时，本机重置仍然成功；
 * 重复重置结果完全一致（幂等），不会重复累计、不会回退、不会复活旧值。
 */
object TimeReset {

    data class Result(val ok: Boolean, val message: String)

    /** @brief 执行一次完整重置；返回是否成功与给家长看的结果文案 */
    fun perform(ctx: Context): Result {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()

        // 1. 本地计时：必须成功，否则视为整体失败并如实告知家长
        val localOk = try {
            UsageRepository.resetAll(app, now)
            true
        } catch (e: Exception) {
            false
        }
        if (!localOk) {
            return Result(false, "重置失败：本机计时数据无法写入，请重启应用后重试")
        }

        // 2. 共享数据集：仅清零当前对象的计时分量（未初始化 / 离线时返回 false）
        val shareOk = try {
            ShareStore.resetActiveTiming(now)
        } catch (e: Exception) {
            false
        }

        // 3. 让界面立即走降级读数，不等下一次 1Hz 状态发布才显示新值
        StateBus.status = UiStatus()

        // 4. 服务运行态与通知栏即时刷新；服务未运行时为空操作
        try {
            GuardService.onTimeReset()
        } catch (e: Exception) {
            // 服务异常不影响已落盘的重置结果
        }

        // 5. 把新纪元立即推送到局域网，其他设备据此同步清零
        try {
            ShareSync.onTimeReset()
        } catch (e: Exception) {
            // 网络异常不影响本机重置结果，后续周期同步会自动补齐
        }

        return Result(
            true,
            if (shareOk) {
                "已重置：今日时长、单次连续、休息倒计时与各应用累计全部归零，并已同步到局域网"
            } else {
                "已重置：今日时长、单次连续、休息倒计时与各应用累计全部归零（局域网共享未启用）"
            }
        )
    }
}

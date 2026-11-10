# -*- coding: utf-8 -*-
"""
计时范围与局域网共享合并的性质自检（算法级验证，不依赖设备）。

复刻 Kotlin 侧的两套核心规则：
1) UsageRepository.sync 的前台片段结算（退出即暂停、重进即继续）；
2) ShareStore / SubjectTiming 的按设备分量 CRDT 合并（不重复、不回退、不丢失）。

运行：python tools/verify_timing.py
"""
from dataclasses import dataclass, field

MAX_DELTA_MS = 5 * 60_000
COMMIT_MS = 5_000


@dataclass
class Ledger:
    total: int = 0
    continuous: int = 0
    per_app: dict = field(default_factory=dict)
    seg_start: int = 0
    seg_pkg: str = ""
    last_commit: int = 0


def close(led: Ledger, now: int) -> None:
    if led.seg_start > 0:
        delta = max(0, min(now - led.seg_start, MAX_DELTA_MS))
        if delta > 0:
            led.total += delta
            led.continuous += delta
            if led.seg_pkg:
                led.per_app[led.seg_pkg] = led.per_app.get(led.seg_pkg, 0) + delta
    led.seg_start = 0
    led.seg_pkg = ""
    led.last_commit = now


def start(led: Ledger, now: int, pkg: str) -> None:
    led.seg_start, led.seg_pkg = now, pkg


def sync(led: Ledger, now: int, fg: str, screen_on: bool, allowed: bool, countable) -> None:
    active = screen_on and allowed and countable(fg)
    if not active:
        if led.seg_start > 0:
            close(led, now)
    elif led.seg_start == 0:
        start(led, now, fg)
    elif led.seg_pkg != fg:
        close(led, now)
        start(led, now, fg)
    elif now - led.last_commit >= COMMIT_MS:
        close(led, now)
        start(led, now, fg)


def snapshot(led: Ledger, now: int):
    delta = max(0, min(now - led.seg_start, MAX_DELTA_MS)) if led.seg_start > 0 else 0
    per = dict(led.per_app)
    if delta > 0 and led.seg_pkg:
        per[led.seg_pkg] = per.get(led.seg_pkg, 0) + delta
    return led.total + delta, led.continuous + delta, per


HOME = "com.android.launcher3"
APP_A, APP_B = "com.game.a", "com.game.b"
countable = lambda p: bool(p) and p != HOME and not p.startswith("com.android.systemui")


def check(name, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + name + (("  | " + extra) if extra else ""))
    if not cond:
        raise AssertionError(name + " " + extra)


B = 1_700_000_000_000  # 用真实量级时间戳，避开 segmentStartMs==0 的哨兵


def run_timing_cases():
    print("=== 一、计时范围：退出 / 停留桌面 / 重新进入 ===")

    # S1 退出到桌面停留后重新进入
    led = Ledger()
    for t in range(0, 61_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    sync(led, B + 60_000, HOME, True, True, countable)       # 立即暂停
    for t in range(61_000, 181_000, 1000):
        sync(led, B + t, HOME, True, True, countable)        # 桌面停留 120 秒
    for t in range(181_000, 221_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)        # 重新进入用 40 秒
    total, cont, per = snapshot(led, B + 221_000)
    check("S1 桌面停留 120s 不计入", per.get(APP_A, 0) == 100_000 and total == 100_000 and cont == 100_000,
          "A=%s total=%s" % (per.get(APP_A, 0), total))
    check("S1 桌面不产生自己的应用时长", HOME not in per)

    # S2 切到别的应用再回来，归属正确、不重复
    led = Ledger()
    for t in range(0, 60_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    for t in range(60_000, 90_000, 1000):
        sync(led, B + t, APP_B, True, True, countable)
    for t in range(90_000, 110_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    total, _, per = snapshot(led, B + 110_000)
    check("S2 A=60s / B=30s / 总=110s", per.get(APP_A, 0) == 80_000 and per.get(APP_B, 0) == 30_000 and total == 110_000,
          "A=%s B=%s total=%s" % (per.get(APP_A, 0), per.get(APP_B, 0), total))

    # S3 后台 / 灭屏 / 锁定不计时
    led = Ledger()
    for t in range(0, 30_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    sync(led, B + 30_000, "", True, False, countable)         # 锁定/未知前台
    for t in range(31_000, 91_000, 1000):
        sync(led, B + t, "", False, True, countable)          # 灭屏
    total, _, per = snapshot(led, B + 91_000)
    check("S3 后台/灭屏/锁定不计时", total == 30_000 and per.get(APP_A, 0) == 30_000, "total=%s" % total)

    # S4 同一时刻重复 sync 幂等，不重复累计
    led = Ledger()
    for t in range(0, 10_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    before = snapshot(led, B + 10_000)[0]
    for _ in range(20):
        sync(led, B + 10_000, APP_A, True, True, countable)
    check("S4 同刻重复 sync 不重复", snapshot(led, B + 10_000)[0] == before,
          "before=%s after=%s" % (before, snapshot(led, B + 10_000)[0]))

    # S5 服务被冻结 3 分钟后恢复，不丢失时长（结算上限 5 分钟）
    led = Ledger()
    sync(led, B, APP_A, True, True, countable)
    sync(led, B + 180_000, APP_A, True, True, countable)
    total, _, per = snapshot(led, B + 180_000)
    check("S5 冻结 3 分钟时长不丢失", total == 180_000 and per.get(APP_A, 0) == 180_000, "total=%s" % total)

    # S6 静止使用（无窗口事件）不因信号过期而丢时长
    led = Ledger()
    for t in range(0, 600_000, 1000):
        sync(led, B + t, APP_A, True, True, countable)
    total, _, _ = snapshot(led, B + 600_000)
    check("S6 应用内静止 10 分钟不丢时长", total == 600_000, "total=%s" % total)


@dataclass
class Clock:
    total: int = 0
    continuous: int = 0
    per_app: dict = field(default_factory=dict)
    active: bool = False
    pkg: str = ""
    updated: int = 0

    def absorb(self, other):
        self.total = max(self.total, other.total)
        self.continuous = max(self.continuous, other.continuous)
        for p, v in other.per_app.items():
            self.per_app[p] = max(self.per_app.get(p, 0), v)
        if other.updated >= self.updated:
            self.active, self.pkg, self.updated = other.active, other.pkg, other.updated


@dataclass
class Timing:
    day: str = "2026-11-09"
    clocks: dict = field(default_factory=dict)

    def absorb(self, other, today):
        if self.day != today:
            self.day, self.clocks = today, {}
        if other.day != today:
            return
        for dev, c in other.clocks.items():
            if dev in self.clocks:
                self.clocks[dev].absorb(c)
            else:
                self.clocks[dev] = Clock(c.total, c.continuous, dict(c.per_app), c.active, c.pkg, c.updated)

    def total(self):
        return sum(c.total for c in self.clocks.values())

    def per_app(self):
        out = {}
        for c in self.clocks.values():
            for p, v in c.per_app.items():
                out[p] = out.get(p, 0) + v
        return out


def run_share_cases():
    print("=== 二、局域网共享合并：不重复 / 不回退 / 不丢失 / 收敛 ===")

    a = Timing()
    a.clocks["devA"] = Clock(60_000, 60_000, {APP_A: 60_000}, False, APP_A, 1)
    b = Timing()
    b.clocks["devB"] = Clock(40_000, 40_000, {APP_B: 40_000}, True, APP_B, 2)

    m1 = Timing(); m1.clocks = {k: Clock(v.total, v.continuous, dict(v.per_app), v.active, v.pkg, v.updated) for k, v in a.clocks.items()}
    m1.absorb(b, "2026-11-09")
    check("C1 两设备分量求和 = 100s", m1.total() == 100_000, "total=%s" % m1.total())
    check("C1 单应用各自的时长都保留", m1.per_app().get(APP_A) == 60_000 and m1.per_app().get(APP_B) == 40_000)

    # 合并顺序不同结果一致（可交换律）
    m2 = Timing(); m2.clocks = {k: Clock(v.total, v.continuous, dict(v.per_app), v.active, v.pkg, v.updated) for k, v in b.clocks.items()}
    m2.absorb(a, "2026-11-09")
    check("C2 并发合并与顺序无关", m1.total() == m2.total() and m1.per_app() == m2.per_app())

    # 同一设备的旧数据/重复上报不回退、不重复
    stale = Timing()
    stale.clocks["devA"] = Clock(10_000, 10_000, {APP_A: 10_000}, False, "", 0)
    m1.absorb(stale, "2026-11-09")
    check("C3 旧版本回放不回退、不重复", m1.total() == 100_000 and m1.per_app().get(APP_A) == 60_000)

    # 新版本上报本设备增量，只增不减
    newer = Timing()
    newer.clocks["devA"] = Clock(90_000, 90_000, {APP_A: 90_000}, True, APP_A, 3)
    m1.absorb(newer, "2026-11-09")
    check("C4 本设备新增量被合并且不重复", m1.total() == 130_000 and m1.per_app().get(APP_A) == 90_000,
          "total=%s" % m1.total())

    # 三设备同时同步到中心服务器：串行合并后收敛
    center = Timing()
    for dev, ms in (("devA", 30_000), ("devB", 20_000), ("devC", 10_000)):
        t = Timing(); t.clocks[dev] = Clock(ms, ms, {APP_A: ms}, False, APP_A, 1)
        center.absorb(t, "2026-11-09")
    check("C5 三设备并发写入收敛为总和", center.total() == 60_000, "total=%s" % center.total())

    # 跨天：旧日分量不进入今天
    t = Timing(day="2026-11-08")
    t.clocks["devA"] = Clock(999_000, 999_000, {APP_A: 999_000}, False, "", 1)
    t.absorb(newer, "2026-11-09")
    check("C6 跨天清零且不串入旧数据", t.total() == 90_000, "total=%s" % t.total())


def hms_full(ms):
    """复刻 Kotlin Formatter.hmsFull()：始终保留时 / 分 / 秒三段，向下取整到整秒"""
    sec = max(0, ms) // 1000
    return "%02d 时 %02d 分 %02d 秒" % (sec // 3600, (sec % 3600) // 60, sec % 60)


def run_display_cases():
    print("=== 三、秒级展示格式：时 / 分 / 秒 ===")
    check("F1 不足 1 秒不进位", hms_full(999) == "00 时 00 分 00 秒", hms_full(999))
    check("F2 整秒与跨分钟保留三段",
          hms_full(59_000) == "00 时 00 分 59 秒" and hms_full(60_000) == "00 时 01 分 00 秒")
    check("F3 跨小时且不省略小时", hms_full(3_661_000) == "01 时 01 分 01 秒", hms_full(3_661_000))
    check("F4 相邻两秒可见变化", hms_full(45_000) != hms_full(46_000),
          "%s -> %s" % (hms_full(45_000), hms_full(46_000)))
    check("F5 负数按 0 处理", hms_full(-5_000) == "00 时 00 分 00 秒")


if __name__ == "__main__":
    run_timing_cases()
    run_share_cases()
    run_display_cases()
    print("ALL CHECKS PASSED")

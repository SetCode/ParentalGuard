# -*- coding: utf-8 -*-
"""生成内置离线中文提示语音（res/raw/*.mp3）。

依赖：pip install edge-tts
用法：python tools/generate_voice.py

生成的音频随 APK 打包，运行时用 MediaPlayer 播放，
不需要设备预装任何文字转语音引擎或中文语音包，也不需要联网。
"""
import asyncio
import os
import sys

import edge_tts

VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "-8%"
PITCH = "+8Hz"

# (资源文件名, 朗读文本)，与 KidPrompt.cueOf 的逐事件文案一一对应
CLIPS = [
    ("voice_block_whitelist", "这个应用不能玩哦，已经帮你回到桌面啦"),
    ("voice_block_limit", "这个应用今天玩够啦，明天再玩吧"),
    ("voice_rest_start", "该休息啦，让眼睛歇一歇，休息好了再玩"),
    ("voice_resting", "还在休息哦，等倒计时结束再玩"),
    ("voice_rest_end", "休息好啦，可以再玩一会儿"),
    ("voice_daily_total", "今天的时间用完啦，明天再玩哦"),
    ("voice_warning", "还有一分钟就要休息啦，准备一下哦"),
    ("voice_test", "这是提示音测试，小朋友听得见吗"),
]


async def main() -> None:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out_dir = os.path.join(root, "app", "src", "main", "res", "raw")
    os.makedirs(out_dir, exist_ok=True)
    for name, text in CLIPS:
        path = os.path.join(out_dir, name + ".mp3")
        comm = edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH)
        await comm.save(path)
        print("%-24s %6d bytes  %s" % (name, os.path.getsize(path), text))
    print("done ->", out_dir)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as exc:  # noqa: BLE001
        print("generate failed:", exc, file=sys.stderr)
        sys.exit(1)

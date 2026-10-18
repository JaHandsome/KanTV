# -*- coding: utf-8 -*-
"""吞吐折算与传输层熔断谓词 —— health 与 verifier 共用的唯一叶子。

历史上这些常量/函数定义在 health.py，verifier 直接 import 其私有名（verifier.py
顶部 import app.health._*），打破了「业务模块互不依赖」的红线；2026-09-25 重构
抽为本叶子模块并改为公开名（原私有名随本次重构一并改名，无兼容出口）。
"""
import statistics

# 传输层异常名白名单（probe_alive 无 .code 时 reason = type(e).__name__）。
#
# 仅"整轮网络不可达"型原因允许触发熔断；HTTP 状态、HTML 错误页、空响应等
# 源端内容/协议失败（'HTTP ...' / 'HTML错误页' / 'HTTP n/空响应'）不在列，
# 保持逐源状态机语义。未知异常名同样不在列（保守：不熔断）。
# 注意：通用 NetPolicyError（URL 语法/重定向/handler 策略拒绝）不在列——它是
# 永久性单源拒绝，不代表整轮网络不可达；只有其子类 NetResolveError（DNS 解析
# 结果不可用）属于传输故障。
TRANSPORT_REASON_NAMES = frozenset((
    'OSError',               # socket.error 别名（Python 3）
    'IOError',
    'URLError',              # urllib.error.URLError（DNS/连接被拒等）
    'TimeoutError',          # 内建超时（Python 3.10+ socket.timeout 别名）
    'timeout',               # socket.timeout（旧版本 __name__）
    'gaierror',              # socket.gaierror（DNS 解析失败）
    'herror',                # socket.herror
    'NetResolveError',       # app.net：解析不可用/非公网/空地址（NetPolicyError 子类）
    'SSLError',              # ssl.SSLError（TLS 握手/读体）
    'SSLEOFError',           # ssl.SSLEOFError（TLS 读体提前 EOF）
    'ConnectionError',
    'ConnectionResetError',
    'ConnectionRefusedError',
    'ConnectionAbortedError',
    'BrokenPipeError',
))


def is_transport_reason(reason):
    """reason 是否属于传输层异常（整轮网络故障）——熔断触发的必要谓词。"""
    return bool(reason) and reason in TRANSPORT_REASON_NAMES


# ---------- v10：分片吞吐探测 ----------
# ratio（下载耗时/分片时长）-> score 折算因子。分段阈值是"8s 分片能否实时下满"
# 的实测语义：<0.5 富余(1.0 不罚)；<0.8 够用(0.9 轻罚)；<1.0 临界(0.5)；
# >=1.0 跑不满(0.05 基本沉底)。失败检查按 2.0 计入窗口（见 THROUGHPUT_FAIL_RATIO）。
THROUGHPUT_FACTORS = (
    (0.5, 1.0),
    (0.8, 0.9),
    (1.0, 0.5),
    (float('inf'), 0.05),
)
# 失败检查参与聚合时的罚分 ratio（"下不动"比"下得慢"更重，故 >1.0 档）。
THROUGHPUT_FAIL_RATIO = 2.0
# "跑不满实时"判定线：窗口内出现 ratio >= 该值的样本即按该样本折算，不再取中位
# （见 throughput_window_factor）。默认值同时是 settings.health.throughput_realtime_ratio。
THROUGHPUT_REALTIME_RATIO = 1.0


def throughput_factor(ratio):
    """ratio -> score 折算因子（THROUGHPUT_FACTORS 分段映射，唯一叶子）。"""
    for ceiling, factor in THROUGHPUT_FACTORS:
        if ratio < ceiling:
            return factor
    return THROUGHPUT_FACTORS[-1][1]


def throughput_window_factor(ratios, realtime_ratio=THROUGHPUT_REALTIME_RATIO):
    """窗口内 ratio 序列 -> 折算因子（聚合唯一叶子）。

    窗口内出现 >= realtime_ratio 的样本（ok=0 的 2.0 罚分天然落在线上）时直接按
    该样本折算：ratio>=1 是"分片下载慢于实时"的确定性结论，不是待平均的噪声，
    取中位会让一次实测劣化被窗口内两次历史好样本稀释掉（2026-09-24 TVB 翡翠台
    58a87b65：窗口 [1.28, 0.78, 0.27] 中位 0.78 -> 因子 0.9 -> score 81，仍是
    投递阵容首位；唯一样本能把它拉下来的是这条按最坏样本折算的规则）。
    窗口内无该样本时沿用中位，保留对单次抖动/探测并发的容错。
    """
    worst = max(ratios)
    if worst >= realtime_ratio:
        return throughput_factor(worst)
    return throughput_factor(statistics.median(ratios))

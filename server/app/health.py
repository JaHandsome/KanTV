# -*- coding: utf-8 -*-
"""HealthMonitor：源健康监控与容灾（P0-3）。

- 轻量探活：首字节探测（limit=512, timeout=8s），#EXTM3U/FLV 头或 HTTP 200 有 body 即存活；
- 状态机（ARCHITECTURE §7.3）：
    active --连续2次失败--> flaky --累计3次失败--> dead（下线，移出线路）
    flaky/dead --连续2次成功--> active（自动回归）
  每次跃迁写 health_events；每次探测写 health_checks；
- dead 源每 N 轮（默认 4x30min）低频复探，恢复通道保留；
- rebuild_lineups：每频道取 active 且 av=video 按 score DESC 的 top max_lines 写 lineups。
- v10（分片吞吐纳入线路评分）：每 8 个探活轮对每频道 lineups 名额 + 候补的 hls 源
  做分片计时探测（下载耗时/分片时长 = ratio，>1 即"分片下不满"），
  按最近 window 次把吞吐折算进 sources.score 本身：窗口内出现过 ratio>=1 的样本
  即按该样本折算，否则取中位（见 throughput.throughput_window_factor）
  （hub payload 与 App 选线都按 score 降序，必须落到 score 上才会生效）。
"""
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin

from app.db import utcnow
from app.logutil import get_logger
from app.net import NetBodyLimitError
from app.throughput import (THROUGHPUT_FAIL_RATIO, THROUGHPUT_REALTIME_RATIO,
                            is_transport_reason, throughput_window_factor)

log = get_logger('health')


def _fetch_playlist(net, url, ua):
    """沿用 NetClient 最终 URL；兼容仅实现 fetch 的离线桩。"""
    fetch_ex = getattr(net, 'fetch_ex', None)
    if fetch_ex is not None:
        status, body, ct, final = fetch_ex(url, ua=ua, limit=393216)
        return status, body, ct, final or url
    status, body, ct = net.fetch(url, ua=ua, limit=393216)
    return status, body, ct, url


def _parse_media_playlist(text):
    """解析媒体清单：返回 (extinf_ms 列表, target_ms, 分片 URI 列表)。

    仅取 #EXTINF 与其后第一条非注释行（分片 URI）；EXTINF 缺失的分片
    对应位置为 None（由调用方用 TARGETDURATION 兜底）。解析写法参考
    verifier.probe_m3u8（相对 URI 用 urljoin 于调用方解析），但本函数只
    解析不计时不判分 —— 吞吐判定是 health 域自己的语义。
    """
    extinf_ms = []
    target_ms = None
    uris = []
    pending = None          # 已读到 EXTINF、待挂到下一个 URI
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('#EXT-X-TARGETDURATION'):
            try:
                target_ms = int(round(float(line.split(':', 1)[1]) * 1000))
            except (IndexError, ValueError):
                target_ms = None
        elif line.startswith('#EXTINF'):
            try:
                pending = int(round(float(line.split(':', 1)[1].split(',', 1)[0]) * 1000))
            except (IndexError, ValueError):
                pending = None
        elif line.startswith('#'):
            continue
        else:
            uris.append(line)
            extinf_ms.append(pending)
            pending = None
    return extinf_ms, target_ms, uris


class HealthMonitor(object):
    """探活 + 状态机 + 线路重排。"""

    def __init__(self, cfg, db, net):
        self.cfg = cfg
        self.db = db
        self.net = net

    # ---------- 探活 ----------
    def probe_alive(self, url, ua=None):
        """首字节轻探：返回 (alive, latency_ms, reason)。

        P1-D 假阳性修复：源退化为 HTML 错误页/CDN 拦截页后不能仅凭 HTTP 200
        判存活。非 m3u8/FLV 头的 200 响应做首字节嗅探（<html / <!DOCTYPE）
        与 Content-Type 检查（text/html），命中即判不存活。
        """
        h = self.cfg.settings['health']
        limit = int(h.get('probe_read_limit', 512))
        t0 = time.time()
        try:
            st, data, ct = self.net.fetch(url, ua=ua, limit=limit)
            latency = int((time.time() - t0) * 1000)
            if data.startswith(b'#EXTM3U') or data[:3] == b'FLV':
                return True, latency, ''
            if st == 200 and len(data) > 0:
                head = data[:512].lower()
                if (b'<html' in head or head.lstrip().startswith(b'<!doctype')
                        or 'text/html' in (ct or '').lower()):
                    return False, latency, 'HTML错误页'
                return True, latency, ''
            return False, latency, 'HTTP %d/空响应' % st
        except Exception as e:  # noqa: BLE001 - 探活异常=不存活, 带原因入历史表
            latency = int((time.time() - t0) * 1000)
            reason = getattr(e, 'code', None)
            return False, latency, ('HTTP %s' % reason) if reason else type(e).__name__

    # ---------- 状态机 ----------
    def on_check(self, source, alive, latency_ms, reason):
        """单源探活结果处理：health_checks 记录 + streak 更新 + 状态跃迁 + health_events。

        P0 优化：全部写入合并为单次原子事务（原来 3-4 次独立 commit，
        既产生多次 fsync，中断还会留下 streak 与 events 不一致）。
        """
        now = utcnow()
        sid = source['id']
        h = self.cfg.settings['health']
        flaky_at = int(h.get('flaky_fail_streak', 2))
        dead_at = int(h.get('dead_fail_streak', 3))
        recover_at = int(h.get('recover_ok_streak', 2))
        status = source['status']
        fail_streak = int(source['fail_streak'] or 0)
        ok_streak = int(source['ok_streak'] or 0)

        with self.db.transaction() as conn:
            conn.execute(
                'INSERT INTO health_checks(source_id, ts, alive, latency_ms, reason) '
                'VALUES (?,?,?,?,?)', (sid, now, 1 if alive else 0, latency_ms, reason))

            if alive:
                ok_streak += 1
                fail_streak = 0
                if status in ('flaky', 'dead') and ok_streak >= recover_at:
                    conn.execute(
                        "UPDATE sources SET status='active', ok_streak=?, fail_streak=0 WHERE id=?",
                        (ok_streak, sid))
                    conn.execute(
                        'INSERT INTO health_events(source_id, ts, event, reason) VALUES (?,?,?,?)',
                        (sid, now, 'online', '连续%d次探活恢复' % ok_streak))
                    log.info('源 #%s %s -> active (%s)', sid, status, source['url'][:60])
                else:
                    conn.execute(
                        'UPDATE sources SET ok_streak=?, fail_streak=0 WHERE id=?', (ok_streak, sid))
            else:
                fail_streak += 1
                ok_streak = 0
                if status == 'active' and fail_streak == flaky_at and flaky_at < dead_at:
                    conn.execute(
                        "UPDATE sources SET status='flaky', fail_streak=?, ok_streak=0 WHERE id=?",
                        (fail_streak, sid))
                    conn.execute(
                        'INSERT INTO health_events(source_id, ts, event, reason) VALUES (?,?,?,?)',
                        (sid, now, 'flaky', '连续%d次探活失败' % fail_streak))
                    log.info('源 #%s active -> flaky (%s)', sid, source['url'][:60])
                elif fail_streak >= dead_at and status != 'dead':
                    conn.execute(
                        "UPDATE sources SET status='dead', fail_streak=?, ok_streak=0 WHERE id=?",
                        (fail_streak, sid))
                    conn.execute(
                        'INSERT INTO health_events(source_id, ts, event, reason) VALUES (?,?,?,?)',
                        (sid, now, 'offline', '连续%d次探活失败' % fail_streak))
                    log.info('源 #%s %s -> dead, 下线 (%s)', sid, status, source['url'][:60])
                else:
                    conn.execute(
                        'UPDATE sources SET fail_streak=?, ok_streak=0 WHERE id=?', (fail_streak, sid))

    # ---------- 整轮熔断 ----------
    @staticmethod
    def _should_circuit_open(results):
        """整轮熔断判定（保守）：可处理结果 >=3、全部失败、且失败原因均为传输层异常。

        results 为 (row, alive, latency, reason) 列表，只含 future 正常返回的探测结果
        （future 异常在收集阶段已剔除，不进入分母）。HTTP 状态 / HTML 错误页 / 空响应
        等源端内容或协议失败只要出现一条即拒绝熔断；任一源存活即拒绝熔断。
        """
        if len(results) < 3:
            return False
        for _row, alive, _latency, reason in results:
            if alive or not is_transport_reason(reason):
                return False
        return True

    def _record_check(self, sid, alive, latency_ms, reason):
        """熔断轮专用：仅追加 health_checks，不动状态机/事件/线路。"""
        self.db.execute(
            'INSERT INTO health_checks(source_id, ts, alive, latency_ms, reason) '
            'VALUES (?,?,?,?,?)',
            (sid, utcnow(), 1 if alive else 0, latency_ms, reason))

    # ---------- 分片吞吐探测（v10） ----------
    def probe_throughput(self, row):
        """分片计时探测：返回 (ok, ratio, seg_duration_ms, download_ms, reason)。

        ratio = 各被测分片 download_ms/EXTINF_ms 的中位（>1 即"分片下不满，直播
        必然累积卡顿"）；EXTINF 缺失用 #EXT-X-TARGETDURATION 兜底；全部失败
        ok=0、ratio=None。master 清单跟随第一个变体（最多 2 层，urljoin 相对
        解析）；AES 加密分片不解密，只计时下载量。
        仅探测 container=='hls' 的源，其余返回 ok=0、reason='skip-non-hls'
        （不写库，由调用方在 SQL 层过滤，这里只做防御）。
        """
        h = self.cfg.settings['health']
        n_segs = max(1, int(h.get('throughput_segments', 2)))
        seg_timeout = float(h.get('throughput_seg_timeout_s', 15))
        max_bytes = int(h.get('throughput_max_bytes', 8388608))
        ua = self.net.ua

        if (row.get('container') or '') != 'hls':
            return 0, None, None, None, 'skip-non-hls'
        url = row['url']

        # master 跟随：取每层首个变体（与 verifier 同写法，最多 2 层）
        try:
            _st, pl, _ct, cur = _fetch_playlist(self.net, url, ua)
            for _depth in range(2):
                if b'#EXT-X-STREAM-INF' not in pl:
                    break
                lines = [l.strip() for l in pl.decode('utf-8', 'ignore').splitlines()
                         if l.strip()]
                sel_uri = None
                for i, l in enumerate(lines):
                    if not l.startswith('#EXT-X-STREAM-INF'):
                        continue
                    for x in lines[i + 1:]:
                        if x.startswith('#EXT-X-STREAM-INF'):
                            break
                        if not x.startswith('#'):
                            sel_uri = x
                            break
                    if sel_uri:
                        break
                if not sel_uri:
                    return 0, None, None, None, 'master无变体'
                cur = urljoin(cur, sel_uri)
                _st, pl, _ct, cur = _fetch_playlist(self.net, cur, ua)
        except Exception as e:  # noqa: BLE001 - 清单拉取失败=探测失败
            return 0, None, None, None, type(e).__name__

        extinf_ms, target_ms, uris = _parse_media_playlist(pl.decode('utf-8', 'ignore'))
        if not uris:
            return 0, None, None, None, '媒体列表无分片'

        # 取末尾 N 个分片（直播窗口末尾 = 最新分片，与播放器实际消费位置一致）
        picked = list(zip(uris, extinf_ms))[-n_segs:]
        ratios = []
        durations = []
        download_total = 0
        last_err = ''
        ok = 0
        for uri, dur_ms in picked:
            seg_url = urljoin(cur, uri)
            if dur_ms is None:
                dur_ms = target_ms
            t0 = time.time()
            try:
                fetch_complete = getattr(self.net, 'fetch_complete', None)
                if fetch_complete is not None:
                    _st, data, _ct = fetch_complete(
                        seg_url, ua=ua, limit=max_bytes, timeout=seg_timeout)
                else:
                    # 兼容历史测试桩；没有完成标志时触顶只能视作证据不足。
                    _st, data, _ct = self.net.fetch(
                        seg_url, ua=ua, limit=max_bytes, timeout=seg_timeout)
                    if len(data) >= max_bytes:
                        raise NetBodyLimitError('segment reached byte limit')
                dl_ms = int((time.time() - t0) * 1000)
                download_total += dl_ms
                if not data:
                    last_err = '空分片'
                    continue
                ok = 1
                if dur_ms:
                    ratios.append(float(dl_ms) / float(dur_ms))
                    durations.append(dur_ms)
                last_err = ''
            except Exception as e:  # noqa: BLE001 - 单分片失败不杀整次探测
                download_total += int((time.time() - t0) * 1000)
                last_err = type(e).__name__
                continue
        if not ok:
            return 0, None, None, download_total, last_err or '全部分片失败'
        ratio = statistics.median(ratios) if ratios else None
        seg_duration = int(statistics.median(durations)) if durations else target_ms
        return 1, ratio, seg_duration, download_total, ''

    def _throughput_targets(self):
        """每频道取 active+video+hls 按 score DESC 前 (lineups 名额+候补) 名的探测目标。"""
        h = self.cfg.settings['health']
        extra = int(h.get('throughput_extra_candidates', 2))
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        limit = max_lines + extra
        channels = self.cfg.channels.get('channels') or []
        seen = set()
        targets = []
        for key in channels:
            for r in self.db.query(
                    "SELECT * FROM sources WHERE channel_key=? AND status='active' "
                    "AND av LIKE 'video%' AND container='hls' "
                    "ORDER BY score DESC, id ASC LIMIT ?", (key, limit)):
                if r['id'] in seen:
                    continue
                seen.add(r['id'])
                targets.append(r)
        return targets

    def _run_throughput_round(self, rnd):
        """一轮吞吐探测：选目标 -> 并发计时 -> 落 throughput_checks -> 折算 score。

        带宽预算 = 每 8 探活轮一次 x 每频道 5 源 x 2 分片 x 8MB；探测异常只记
        日志不杀整轮（调度 tick 粒度兜底）。返回单行汇总用的 dict。
        """
        targets = self._throughput_targets()
        if not targets:
            return {'probed': 0}
        concurrency = int(self.cfg.settings['limits']['net_concurrency'])
        ua = self.net.ua
        ok_count = 0
        fail_count = 0
        results = []
        with ThreadPoolExecutor(max_workers=concurrency) as ex:
            futs = {ex.submit(self.probe_throughput, row): row for row in targets}
            for f in as_completed(futs):
                row = futs[f]
                try:
                    res = f.result()
                except Exception as e:  # noqa: BLE001 - 单源异常不杀整轮
                    log.error('吞吐探测任务异常 source=%s: %s', row['id'], e)
                    fail_count += 1
                    continue
                results.append((row['id'],) + res)
        now = utcnow()
        for sid, ok, ratio, seg_ms, dl_ms, reason in results:
            try:
                self.db.execute(
                    'INSERT INTO throughput_checks(source_id, ts, seg_duration_ms, '
                    'download_ms, ratio, ok, reason) VALUES (?,?,?,?,?,?,?)',
                    (sid, now, seg_ms, dl_ms, ratio, ok, reason))
            except Exception as e:  # noqa: BLE001
                log.error('吞吐结果写入异常 source=%s: %s', sid, e)
                continue
            if ok:
                ok_count += 1
            else:
                fail_count += 1
        self.apply_throughput_scores()
        summary = {'probed': len(results), 'ok': ok_count, 'fail': fail_count}
        log.info('吞吐探测轮 %d: %s', rnd, summary)
        return summary

    def apply_throughput_scores(self):
        """按最近 window 次吞吐检查折算 sources.score（值变化才 UPDATE）。

        对每个有吞吐记录的源：取最近 window 次检查，ok=0 的失败检查按 2.0 罚分
        计入（"下不动"按比"下得慢"更重处理，与全部失败 ratio=NULL 的入库语义
        区分开 —— NULL 不入窗口，由调用方显式给罚分值）；窗口 ratio 序列 ->
        throughput_window_factor 折算 -> 新 score = round(base_score x factor, 1)。
        base_score 是 v10 迁移时从 score 快照的"纯净基线"（防复利漂移），
        为 NULL（迁移后新入库源）时用当前 score 并顺手回填。
        只在 score 实际变化时 UPDATE，避免每轮无意义写放大。

        ⚠️ 折算必须落在 score 上（而不是只改 lineups rank）：hub payload 路由与
        App 侧选线都按 score 降序。
        """
        h = self.cfg.settings['health']
        window = max(1, int(h.get('throughput_window', 3)))
        realtime = float(h.get('throughput_realtime_ratio',
                               THROUGHPUT_REALTIME_RATIO))
        rows = self.db.query(
            'SELECT s.id AS id, s.score AS score, s.base_score AS base_score '
            'FROM sources s WHERE EXISTS (SELECT 1 FROM throughput_checks t '
            'WHERE t.source_id = s.id)')
        updated = 0
        for r in rows:
            checks = self.db.query(
                'SELECT ok, ratio FROM throughput_checks WHERE source_id=? '
                'ORDER BY ts DESC, id DESC LIMIT ?', (r['id'], window))
            if not checks:
                continue
            ratios = []
            for c in checks:
                if c['ok']:
                    if c['ratio'] is not None:
                        ratios.append(float(c['ratio']))
                else:
                    ratios.append(THROUGHPUT_FAIL_RATIO)
            if not ratios:
                continue
            factor = throughput_window_factor(ratios, realtime)
            base = r['base_score']
            if base is None:
                base = r['score']
            new_score = round(float(base) * factor, 1)
            if abs(new_score - float(r['score'])) < 1e-9 and r['base_score'] is not None:
                continue
            self.db.execute(
                'UPDATE sources SET score=?, base_score=? WHERE id=?',
                (new_score, base, r['id']))
            updated += 1
        if updated:
            log.info('吞吐折算完成: %d/%d 源 score 更新', updated, len(rows))
        return updated


    # ---------- 线路重排 ----------
    def rebuild_lineups(self):
        """每频道取 active+video 按 score DESC 的 top max_lines 写 lineups（DELETE+INSERT）。"""
        now = utcnow()
        max_lines = int(self.cfg.settings['limits']['max_lines_per_channel'])
        channels = self.cfg.channels.get('channels') or []
        for key in channels:
            cands = self.db.query(
                "SELECT id FROM sources WHERE channel_key=? AND status='active' "
                "AND av LIKE 'video%' ORDER BY score DESC, id ASC LIMIT ?",
                (key, max_lines))
            self.db.execute('DELETE FROM lineups WHERE channel_key=?', (key,))
            if cands:
                self.db.executemany(
                    'INSERT INTO lineups(channel_key, source_id, rank, updated_at) '
                    'VALUES (?,?,?,?)',
                    [(key, c['id'], i, now) for i, c in enumerate(cands)])

    # ---------- 主流程 ----------
    def run(self):
        """一轮探活：active/flaky 全量 + dead 低频复探 -> 状态机 -> 线路重排。"""
        h = self.cfg.settings['health']
        every = max(1, int(h.get('dead_probe_every_rounds', 4)))
        rnd = int(self.db.get_meta('probe_round', '0') or 0) + 1
        self.db.set_meta('probe_round', rnd)
        include_dead = (rnd % every == 0)

        if include_dead:
            # P0 修复：channel_key != '' 与 verifier 对齐——非目标频道的源
            # 永远进不了 lineups（builder 只按 channels 清单取），探活它们纯浪费预算
            targets = self.db.query(
                "SELECT * FROM sources WHERE status IN ('active','flaky','dead') "
                "AND channel_key != ''")
        else:
            targets = self.db.query(
                "SELECT * FROM sources WHERE status IN ('active','flaky') "
                "AND channel_key != ''")
        if not targets:
            log.info('探活轮 %d: 无目标源', rnd)
            self.rebuild_lineups()
            return {'round': rnd, 'probed': 0, 'alive': 0, 'dead': 0,
                    'circuit_open': False}

        concurrency = int(self.cfg.settings['limits']['net_concurrency'])
        ua = self.net.ua
        stats = {'round': rnd, 'probed': len(targets), 'alive': 0, 'dead': 0}

        def work(row):
            alive, latency, reason = self.probe_alive(row['url'], ua)
            return row, alive, latency, reason

        # 先收集整轮结果再做熔断判定。future 异常记日志并跳过（旧行为），
        # 不进 results —— 未知异常不参与熔断分母。
        results = []
        with ThreadPoolExecutor(max_workers=concurrency) as ex:
            futs = [ex.submit(work, row) for row in targets]
            for f in as_completed(futs):
                try:
                    results.append(f.result())
                except Exception as e:  # noqa: BLE001 - 单源异常不杀整轮
                    log.error('探活任务异常: %s', e)
                    continue

        if self._should_circuit_open(results):
            # 整轮网络不可达：只落 health_checks，冻结状态机/事件/线路，避免把
            # "本次整体断网"误判成每个源各自退化为 dead。
            log.warning('探活轮 %d: 整轮传输层失败(%d 源), 触发熔断', rnd, len(results))
            for row, alive, latency, reason in results:
                try:
                    self._record_check(row['id'], alive, latency, reason)
                except Exception as e:  # noqa: BLE001
                    log.error('熔断结果写入异常 source=%s: %s', row['id'], e)
                    continue
                if alive:
                    stats['alive'] += 1
                else:
                    stats['dead'] += 1
            stats['circuit_open'] = True
            log.info('探活轮 %d 完成(熔断): %s', rnd, stats)
            return stats

        for row, alive, latency, reason in results:
            try:
                self.on_check(row, alive, latency, reason)
            except Exception as e:  # noqa: BLE001
                log.error('探活结果处理异常 source=%s: %s', row['id'], e)
                continue
            if alive:
                stats['alive'] += 1
            else:
                stats['dead'] += 1
        # v10 吞吐探测：熔断轮不跑（网络整体不可达时测出来的吞吐无意义）；
        # 正常轮按 every_rounds 节流，在 rebuild_lineups 之前完成 score 折算
        h = self.cfg.settings['health']
        tp_every = max(1, int(h.get('throughput_every_rounds', 8)))
        if h.get('throughput_enabled') and rnd % tp_every == 0:
            try:
                self._run_throughput_round(rnd)
            except Exception as e:  # noqa: BLE001 - 吞吐失败不影响本轮线路重排
                log.error('吞吐探测轮 %d 异常: %s', rnd, e)
        self.rebuild_lineups()
        stats['circuit_open'] = False
        log.info('探活轮 %d 完成: %s', rnd, stats)
        return stats

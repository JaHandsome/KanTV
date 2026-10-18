#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线单测聚合入口（Python 无 pip 依赖；无需本机 settings、令牌、种子或订阅）。

测试使用公开 config 元数据与 offline_fixtures 生成的合成 settings/DRM/种子。
HTTP 测试仅绑定本机回环地址，不请求外部服务。
TLS 回归使用本机 openssl 命令生成临时合成 CA 与证书。

用法（项目根目录下执行）:
    python3.12 server/tests/run_offline.py
"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)


def main():
    loader = unittest.TestLoader()
    suite = loader.discover(os.path.join(ROOT, 'tests'), pattern='test_*.py')
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    print('\n===== 离线单测汇总 =====')
    print('用例总数: %d | 失败: %d | 错误: %d | 跳过: %d'
          % (result.testsRun, len(result.failures), len(result.errors), len(result.skipped)))
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    sys.exit(main())

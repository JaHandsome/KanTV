#!/usr/bin/env python3
"""从代码默认值生成空服务配置；不读取本地 settings、令牌、来源或数据。"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import (  # noqa: E402
    DEFAULT_CHANNELS,
    DEFAULT_REPOS,
    DEFAULT_RULES,
    DEFAULT_SETTINGS,
    DEFAULT_URL_LISTS,
)


def initialize(destination):
    """仅创建全新配置文件；遇到现有文件或符号链接时拒绝覆盖。"""
    destination = Path(destination)
    defaults = {
        'settings.json': DEFAULT_SETTINGS,
        'channels.json': DEFAULT_CHANNELS,
        'repos.json': DEFAULT_REPOS,
        'rules.json': DEFAULT_RULES,
        'url_lists.json': DEFAULT_URL_LISTS,
    }
    if any((destination / name).exists() or (destination / name).is_symlink()
           for name in defaults):
        raise FileExistsError('目标已有配置；请使用新的空目录，不覆盖现有部署')
    destination.mkdir(parents=True, exist_ok=True)
    for name, default in defaults.items():
        with (destination / name).open('x', encoding='utf-8') as handle:
            json.dump(default, handle, ensure_ascii=False, indent=2)
            handle.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='新配置目录')
    args = parser.parse_args()
    try:
        initialize(args.output)
    except OSError as error:
        parser.exit(1, '初始化失败：%s\n' % error)
    print('已生成空配置，Hub 默认关闭；请按部署说明配置自己的来源。')


if __name__ == '__main__':
    main()

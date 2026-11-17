#!/usr/bin/env python3
"""Create a fresh local database from public schema and dictionaries."""
from pathlib import Path

from package_share import create_database


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    target = root / 'db/getjobs.db'
    create_database(target, root / 'distribution/defaults')
    print('已创建空白本地数据库 db/getjobs.db；未导入账号、登录态或个人资料。')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""一条命令打包：自动升版本 → 构建 → 跑测试 → 复制到 dist。

用法：
    python scripts/release.py                # 补丁号 +1，例如 1.1.1 → 1.1.2
    python scripts/release.py --minor        # 次版本 +1，例如 1.1.1 → 1.2.0
    python scripts/release.py --major        # 主版本 +1，例如 1.1.1 → 2.0.0
    python scripts/release.py --no-bump      # 不升版本，按当前版本重新打包
    python scripts/release.py --keep-old     # 保留 dist 里的旧安装包

为什么要自动升版本：
    同一个 versionCode 的 APK 覆盖安装时，系统可能认为「没有变化」而跳过，
    让人误以为新包没生效。versionCode 必须严格递增。

注意：这个脚本用 Gradle launcher jar 直接调，不走 gradlew，
因为本机 Git Bash 是精简版，缺少 uname/xargs，gradlew 的 shell 脚本跑不起来。
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROPS = ROOT / "version.properties"
APK_OUT = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DIST = ROOT / "dist"
APK_PREFIX = "电视护眼管家"

# 工具链放在工程上一级，故意不进仓库（体积大、且各机器路径不同）
TOOLCHAIN = ROOT.parent / ".toolchain"
JDK = TOOLCHAIN / "jdk" / "jdk-17.0.20.1+1" / "bin" / "java.exe"
GRADLE_LAUNCHER = TOOLCHAIN / "gradle" / "gradle-8.7" / "lib" / "gradle-launcher-8.7.jar"
SDK_DIR = TOOLCHAIN / "android-sdk"


def read_props() -> dict:
    data = {}
    for line in PROPS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            data[k.strip()] = v.strip()
    return data


def write_props(code: int, name: str) -> None:
    PROPS.write_text(
        "# 打包版本号。每次打包前由 scripts/release.py 自动递增，不要手改。\n"
        "# versionCode 必须单调递增，否则手机上覆盖安装会失败。\n"
        f"versionCode={code}\n"
        f"versionName={name}\n",
        encoding="utf-8",
    )


def bump(name: str, kind: str) -> str:
    """按 kind 升一级版本号。'patch' / 'minor' / 'major'"""
    parts = [int(x) for x in re.findall(r"\d+", name)[:3]]
    while len(parts) < 3:
        parts.append(0)
    major, minor, patch = parts

    if kind == "major":
        major, minor, patch = major + 1, 0, 0
    elif kind == "minor":
        minor, patch = minor + 1, 0
    else:
        patch += 1
    return f"{major}.{minor}.{patch}"


def run(cmd: list, label: str) -> None:
    print(f"\n=== {label}")
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        print(f"\n[失败] {label}，退出码 {result.returncode}")
        sys.exit(result.returncode)


def main() -> None:
    parser = argparse.ArgumentParser(description="打包并自动升版本")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--minor", action="store_true", help="次版本 +1")
    group.add_argument("--major", action="store_true", help="主版本 +1")
    group.add_argument("--no-bump", action="store_true", help="不升版本")
    parser.add_argument("--keep-old", action="store_true", help="保留 dist 旧安装包")
    parser.add_argument("--skip-tests", action="store_true", help="跳过单元测试")
    args = parser.parse_args()

    for path, hint in (
        (JDK, "JDK 17"),
        (GRADLE_LAUNCHER, "Gradle 8.7"),
        (SDK_DIR, "Android SDK"),
    ):
        if not path.exists():
            print(f"[错误] 找不到 {hint}：{path}")
            print(f"       工具链应位于 {TOOLCHAIN}")
            sys.exit(1)

    props = read_props()
    old_code = int(props.get("versionCode", "1"))
    old_name = props.get("versionName", "1.0.0")

    if args.no_bump:
        new_name = old_name
        new_code = old_code
    else:
        kind = "major" if args.major else "minor" if args.minor else "patch"
        new_name = bump(old_name, kind)
        new_code = old_code + 1

    print(f"版本：{old_name} ({old_code})  →  {new_name} ({new_code})")
    if not args.no_bump:
        write_props(new_code, new_name)

    gradle = [
        str(JDK),
        "-classpath", str(GRADLE_LAUNCHER),
        "org.gradle.launcher.GradleMain",
        "-p", str(ROOT),
        "--console=plain",
    ]

    if not args.skip_tests:
        run(gradle + ["testDebugUnitTest"], "单元测试")

    run(gradle + ["assembleDebug"], "构建 Debug 包")

    if not APK_OUT.exists():
        print(f"[错误] 没找到产物：{APK_OUT}")
        sys.exit(1)

    DIST.mkdir(exist_ok=True)
    target = DIST / f"{APK_PREFIX}-{new_name}-debug.apk"

    if not args.keep_old:
        # 只保留最新一个包，避免 dist 越堆越大分不清
        for old in DIST.glob(f"{APK_PREFIX}-*.apk"):
            old.unlink()
            print(f"清理旧包：{old.name}")

    shutil.copy2(APK_OUT, target)
    size_mb = target.stat().st_size / 1024 / 1024
    print(f"\n完成：{target}")
    print(f"      版本 {new_name} (versionCode {new_code})，{size_mb:.1f} MB")


if __name__ == "__main__":
    main()

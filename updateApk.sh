#!/bin/bash
# 构建门禁：单元测试 + 双 flavor 出包，产物复制到 app/build/。
# Git Bash 一键入口；纯 cmd 环境用 scripts/build-apk.bat（二者等价）。
# 上游发布时代（sed 更新 release.html + git push）的流程已废弃，不再保留注释残骸。
set -e
cd "$(dirname "$0")"

echo "[1/3] unit tests"
./gradlew :app:testArmv8-dotprodDebugUnitTest

echo "[2/3] assemble release APKs (armv8 + armv8-dotprod)"
./gradlew assembleArmv8Release assembleArmv8-dotprodRelease

echo "[3/3] copy APKs to app/build/"
rm -f app/build/XQDK_*.apk
cp app/build/outputs/apk/armv8/release/*.apk app/build/
cp app/build/outputs/apk/armv8-dotprod/release/*.apk app/build/
ls -l app/build/XQDK_*.apk

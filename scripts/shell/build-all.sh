#!/usr/bin/env bash
# 全模块编译 + 打包（JDK17 + IntelliJ 内置 maven，跳过测试）
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
export JAVA_HOME=/Users/shihang/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
cd /Users/shihang/IdeaProjects/helloai || exit 1
"$MVN" -pl helloai-start -am package -DskipTests 2>&1 | tail -30

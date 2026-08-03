#!/usr/bin/env bash
# 运行 helloai-core 指定单测（JDK17 + IntelliJ 内置 maven）
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
export JAVA_HOME=/Users/shihang/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
cd /Users/shihang/IdeaProjects/helloai || exit 1
"$MVN" -pl helloai-core -am test -DskipTests=false -Dtest="$1" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -100

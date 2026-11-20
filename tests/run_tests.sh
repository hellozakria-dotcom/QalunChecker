#!/bin/sh
# Runs the checking-engine tests on a normal computer (Java 17 + Python 3).
set -e
cd "$(dirname "$0")"
[ -d kfgqpc ] || git clone --depth 1 https://github.com/thetruetruth/quran-data-kfgqpc kfgqpc
python3 make_fixtures.py kfgqpc fixtures
rm -rf out && mkdir out
javac -encoding UTF-8 -d out ../app/src/main/java/com/qalun/checker/core/*.java TrackerTest.java
java -Dstdout.encoding=UTF-8 -cp out TrackerTest fixtures ../app/src/main/assets

#!/bin/bash
# Runs a command; on failure, posts its last output lines as a build annotation (readable without login).
label="$1"; shift
log=$(mktemp)
"$@" 2>&1 | tee "$log"
code=${PIPESTATUS[0]}
grep -h "^::notice" "$log" >/dev/null 2>&1
if [ "$code" -ne 0 ]; then
  msg=$(grep -v "^\s*$" "$log" | tail -40 | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"}{print}')
  echo "::error title=$label failed::$msg"
fi
exit "$code"

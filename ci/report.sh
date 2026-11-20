#!/bin/bash
# Runs a command; on failure, posts its last output lines as build annotations (readable without login).
label="$1"; shift
log=$(mktemp)
"$@" > "$log" 2>&1
code=$?
cat "$log"
if [ "$code" -ne 0 ]; then
  grep -v '^\s*$' "$log" | grep -v '^::' | tail -9 | cut -c1-900 | tr -d '\r' | sed 's/%/%25/g; s/::/: :/g' | while IFS= read -r line; do
    echo "::error title=$label::$line"
  done
fi
exit "$code"

#!/usr/bin/env bash
# Host-side driver: attaches jdb to the suspended kotlin debug container, sets a
# breakpoint, resumes, and dumps the call stack + locals when the breakpoint hits.
set -u
FIFO=/tmp/opencode/dbg-kotlin.fifo
OUT=/tmp/opencode/jdb-kotlin.out
BP="${1:-org.scip_code.scip_java.kotlinc.ScipVisitor.visitNamedFunction}"

rm -f "$FIFO" "$OUT"
mkfifo "$FIFO"

jdb -attach localhost:5006 < "$FIFO" > "$OUT" 2>&1 &
JDBPID=$!

exec 3> "$FIFO"   # opens writer; unblocks jdb's read

send() { echo "$1" >&3; sleep 0.6; }

send "stop in $BP"
send "resume"

for _ in $(seq 1 90); do
  grep -q "断点命中" "$OUT" && break
  sleep 1
done

echo "===== after resume / breakpoint hit ====="
cat "$OUT"

if grep -q "断点命中" "$OUT"; then
  send "where"
  sleep 0.6
  send "locals"
  sleep 0.6
fi
send "quit"
exec 3>&-

sleep 2
kill "$JDBPID" 2>/dev/null

echo ""
echo "===== final (where / locals) ====="
cat "$OUT"
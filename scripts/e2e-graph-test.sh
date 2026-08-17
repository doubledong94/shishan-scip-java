#!/usr/bin/env bash
# 独立端到端测试：scip-java fork 聚合期直写 Neo4j。
#
# 流程：构建 installDist → 起一次性 Neo4j → 用 shishanMcp 的 scip 镜像跑 fork index
#       → cypher 断言图数据 → 清理（容器 + 临时目录）。
#
# 用法：
#   ./scripts/e2e-graph-test.sh [--skip-build] [--neo4j-port 17687]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEO4J_PORT=17687
NEO4J_PASS=testpass
NEO4J_IMAGE=neo4j:5-community
SCIP_IMAGE=shishan-scip:local
SAMPLE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/scip-graph-sample-XXXXXX")"
NEO4J_NAME="scip-graph-test-neo4j"

SKIP_BUILD=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift;;
    --neo4j-port) NEO4J_PORT="$2"; shift 2;;
    *) echo "未知参数: $1" >&2; exit 1;;
  esac
done

cleanup() {
  docker rm -f "$NEO4J_NAME" >/dev/null 2>&1 || true
  rm -rf "$SAMPLE_DIR"
}
trap cleanup EXIT

echo "==> 构建 installDist"
if [ "$SKIP_BUILD" -eq 0 ]; then
  (cd "$ROOT" && ./gradlew :scip-java:installDist -q)
fi
DIST="$ROOT/scip-java/build/install/scip-java"
if [ ! -x "$DIST/bin/scip-java" ]; then
  echo "错误: 没有可执行的 $DIST/bin/scip-java（先构建）" >&2; exit 1
fi

echo "==> 生成样例项目（$SAMPLE_DIR）"
mkdir -p "$SAMPLE_DIR/src/main/java/com/example"
cat > "$SAMPLE_DIR/settings.gradle" <<'EOF'
rootProject.name = 'graph-sample'
EOF
cat > "$SAMPLE_DIR/build.gradle" <<'EOF'
apply plugin: 'java'
EOF
cat > "$SAMPLE_DIR/gradle.properties" <<'EOF'
org.gradle.java.home=/opt/jdk21
org.gradle.java.installations.paths=/opt/jdk21,/opt/jdk11
org.gradle.java.installations.auto-download=false
EOF
cat > "$SAMPLE_DIR/src/main/java/com/example/Foo.java" <<'EOF'
package com.example;

public class Foo {
    private int counter;

    public int incr(int delta) {
        return counter + delta;
    }

    public void bar() {
        int x = 0;
        if (x > 0) {
            incr(x);
        } else {
            incr(1);
        }
    }
}
EOF

echo "==> 启动一次性 Neo4j（:${NEO4J_PORT}）"
docker run -d --name "$NEO4J_NAME" \
  -p "$NEO4J_PORT:7687" \
  -e NEO4J_AUTH="neo4j/$NEO4J_PASS" \
  -e NEO4J_server_memory_heap_max__size=512M \
  "$NEO4J_IMAGE" >/dev/null
echo -n "   等待 Neo4j 就绪"
for i in $(seq 1 60); do
  if docker exec "$NEO4J_NAME" cypher-shell -u neo4j -p "$NEO4J_PASS" "RETURN 1" >/dev/null 2>&1; then
    echo " ok"; break
  fi
  if [ "$i" = 60 ]; then echo " 超时"; docker logs "$NEO4J_NAME" | tail -20; exit 1; fi
  echo -n "."; sleep 2
done

echo "==> 用 fork 对样例项目索引并直写 Neo4j"
docker run --rm \
  -v "$SAMPLE_DIR:$SAMPLE_DIR" \
  -v "$DIST:/app/scip-java:ro" \
  -e "NEO4J_URI=bolt://host.docker.internal:${NEO4J_PORT}" \
  -e "NEO4J_USER=neo4j" \
  -e "NEO4J_PASSWORD=$NEO4J_PASS" \
  -w "$SAMPLE_DIR" \
  --entrypoint /bin/sh \
  "$SCIP_IMAGE" -c \
    "/app/scip-java/bin/scip-java index --output $SAMPLE_DIR/index.scip" 2>&1 | \
    grep -E "error|wrote code graph|BUILD" || true

echo "==> 断言图数据"
PROJECT="$(basename "$SAMPLE_DIR")"
q() {
  docker exec "$NEO4J_NAME" cypher-shell -u neo4j -p "$NEO4J_PASS" --format plain "$1" 2>/dev/null | grep -E '^[0-9]+$' | tail -1
}

CLASS_N=$(q "MATCH (c:Class {projectId:'$PROJECT'}) RETURN count(c)")
METHOD_N=$(q "MATCH (m:Method {projectId:'$PROJECT'}) RETURN count(m)")
CALLS_N=$(q "MATCH (:CalledMethod {projectId:'$PROJECT'})-[:CALLS]->(:Method) RETURN count(*)")
IF_N=$(q "MATCH (c:Condition {projectId:'$PROJECT', kind:'IF'}) RETURN count(c)")
FIELDS_N=$(q "MATCH (f:Field {projectId:'$PROJECT'}) RETURN count(f)")

echo "  Class=$CLASS_N Method=$METHOD_N Field=$FIELDS_N CalledMethod->CALLS=$CALLS_N Condition(IF)=$IF_N"

[ "${CLASS_N:-0}" -ge 1 ] || { echo "FAIL: 期望至少 1 个 Class"; exit 1; }
[ "${METHOD_N:-0}" -ge 3 ] || { echo "FAIL: 期望至少 3 个 Method（构造器+incr+bar）"; exit 1; }
[ "${FIELDS_N:-0}" -ge 1 ] || { echo "FAIL: 期望至少 1 个 Field"; exit 1; }
[ "${CALLS_N:-0}" -ge 2 ] || { echo "FAIL: 期望至少 2 条 CALLS（incr(x)/incr(1)）"; exit 1; }
[ "${IF_N:-0}" -ge 1 ] || { echo "FAIL: 期望至少 1 个 IF 分支"; exit 1; }

echo "PASS"

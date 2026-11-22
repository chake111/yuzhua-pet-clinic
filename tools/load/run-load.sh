#!/usr/bin/env bash
# =====================================================================
#  k6 压测一键运行脚本 (Git Bash / Linux)
#
#  使用独立 compose 项目名 yuzhuaload, 不碰演示栈 yuzhua 的容器和卷。
#  流程:
#    1) 启动 db + backend, 等待健康
#    2) 导入 fixture.sql (100 只宠物 / 2 条排班 / 压测账号)
#    3) race:     100 个号, 100 VU, 期望 100 成功
#    4) oversell: 20 个号,  100 VU, 期望 20 成功 + 80 拒绝
#    5) verify.sql 校验号源守恒, 出现 FAIL 则整体退出码 1
#
#  默认结束后 down -v 清理; KEEP=1 保留容器与卷供人工检查。
# =====================================================================
set -euo pipefail

PROJECT=yuzhuaload
NETWORK="${PROJECT}_default"
BASE_URL=http://backend:8081/api

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOAD_DIR="${ROOT_DIR}/tools/load"
# 证据按运行日期归档 (AGENTS.md 规矩二), 复跑不写回旧轮次目录
EVIDENCE_DIR="${EVIDENCE_DIR:-${ROOT_DIR}/docs/test-evidence/$(date +%Y%m%d)-it-load}"

# k6 的 --summary-export 会把 setup() 返回的登录 token 写进 setup_data；
# 证据要能公开提交，复制到证据目录后立刻删掉该字段
strip_setup_data() {
  node -e '
const fs = require("fs");
const file = process.argv[1];
const data = JSON.parse(fs.readFileSync(file, "utf8"));
delete data.setup_data;
fs.writeFileSync(file, JSON.stringify(data, null, 2) + "\n");
' "$1"
}

cd "$ROOT_DIR"
mkdir -p "$EVIDENCE_DIR"

# 干净 checkout 没有根 .env 时，compose.yaml 的 :? 必填变量会让 preflight 直接失败。
# 压测栈是隔离的，这里提供测试专用默认值；外部已设置同名变量时优先使用外部值。
export YUZHUA_MYSQL_ROOT_PASSWORD="${YUZHUA_MYSQL_ROOT_PASSWORD:-k6-root-password}"
export YUZHUA_DB_PASSWORD="${YUZHUA_DB_PASSWORD:-k6-db-password}"
export YUZHUA_JWT_SECRET="${YUZHUA_JWT_SECRET:-k6-load-test-jwt-secret-0123456789abcdef}"

# Docker Desktop + Git Bash: -v 的宿主路径用 Windows 形式, 容器内路径禁止被 MSYS 改写
if command -v cygpath >/dev/null 2>&1; then
  LOAD_DIR_DOCKER="$(cygpath -m "${LOAD_DIR}")"
else
  LOAD_DIR_DOCKER="${LOAD_DIR}"
fi

cleanup() {
  local rc=$?
  if [[ "${KEEP:-0}" == "1" ]]; then
    echo "KEEP=1: 保留 ${PROJECT} 的容器与卷"
    echo "  清理: docker compose -p ${PROJECT} down -v"
  else
    echo "清理 ${PROJECT} 的容器与卷..."
    docker compose -p "$PROJECT" down -v --remove-orphans || true
  fi
  exit "$rc"
}
trap cleanup EXIT

# 确认 compose 解析出的网络名与 k6 容器使用的网络一致
if ! docker compose -p "$PROJECT" config 2>/dev/null | grep -q "name: ${NETWORK}"; then
  echo "错误: compose 网络名不是 ${NETWORK}, 请先检查 docker compose -p ${PROJECT} config" >&2
  exit 1
fi

echo "==> 启动 db + backend (项目 ${PROJECT})"
# 只启动压测必需的两个服务: web 与演示栈共用 127.0.0.1:8088, 压测也不经过前端。
docker compose -p "$PROJECT" up -d --build --wait --wait-timeout 300 db backend

echo "==> 导入 fixture.sql"
docker compose -p "$PROJECT" exec -T db \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot yuzhua' \
  < "${LOAD_DIR}/fixture.sql" 2>&1 | tee "${EVIDENCE_DIR}/fixture.log"
# mysql 成功导入时没有输出, 补一行标记, 避免 fixture.log 为空无法核对
echo "fixture.sql 导入完成: schedule 9001=100 号, 9002=20 号, pet 90001..90100" \
  | tee -a "${EVIDENCE_DIR}/fixture.log"

run_k6() {
  local scenario="$1"
  local summary="summary-${scenario}.json"
  # 先删旧汇总：本次 k6 若在写文件前失败，不能把上一次的 summary 当成本次证据
  rm -f "${LOAD_DIR}/${summary}" "${EVIDENCE_DIR}/${summary}"
  local rc
  set +e
  MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "${LOAD_DIR_DOCKER}:/scripts" \
    -e BASE_URL="$BASE_URL" \
    -e SCENARIO="$scenario" \
    grafana/k6 run "--summary-export=/scripts/${summary}" /scripts/booking-race.js \
    2>&1 | tee "${EVIDENCE_DIR}/${scenario}.log"
  rc=${PIPESTATUS[0]}
  set -e
  if [[ -f "${LOAD_DIR}/${summary}" ]]; then
    cp -f "${LOAD_DIR}/${summary}" "${EVIDENCE_DIR}/${summary}"
    if ! strip_setup_data "${EVIDENCE_DIR}/${summary}"; then
      echo "错误: 无法从 ${summary} 删除 setup_data，证据可能带 token" >&2
      return 1
    fi
    # k6 把汇总写在挂载目录里；复制到证据目录后立刻删掉，避免临时文件被误提交
    rm -f "${LOAD_DIR}/${summary}"
  fi
  return "$rc"
}

echo "==> race: 100 个号 / 100 VU"
race_rc=0
run_k6 race || race_rc=$?

echo "==> oversell: 20 个号 / 100 VU"
oversell_rc=0
run_k6 oversell || oversell_rc=$?

echo "==> 执行 verify.sql"
verify_rc=0
set +e
docker compose -p "$PROJECT" exec -T db \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot yuzhua' \
  < "${LOAD_DIR}/verify.sql" 2>&1 | tee "${EVIDENCE_DIR}/verify.log"
verify_rc=${PIPESTATUS[0]}
set -e

final_rc=0
if [[ "$race_rc" -ne 0 ]]; then
  echo "错误: race 场景未通过 (k6 exit ${race_rc})" >&2
  final_rc=1
fi
if [[ "$oversell_rc" -ne 0 ]]; then
  echo "错误: oversell 场景未通过 (k6 exit ${oversell_rc})" >&2
  final_rc=1
fi
if [[ "$verify_rc" -ne 0 ]]; then
  echo "错误: verify.sql 执行失败" >&2
  final_rc=1
fi
if grep -q 'FAIL' "${EVIDENCE_DIR}/verify.log"; then
  echo "错误: verify.sql 存在 FAIL 行" >&2
  final_rc=1
fi

if [[ "$final_rc" -eq 0 ]]; then
  echo "压测通过: race + oversell + 号源守恒校验全部 PASS"
else
  echo "压测失败, 详见 ${EVIDENCE_DIR}" >&2
fi
exit "$final_rc"
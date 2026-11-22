#!/usr/bin/env bash
# =====================================================================
#  稳态读混合压测一键运行脚本 (Git Bash / Linux)
#
#  使用独立 compose 项目名 yuzhuaperf, 不碰演示栈 yuzhua,
#  也不复用 11-16 集成压测栈 yuzhuaload 的容器与卷。
#  流程:
#    1) 以 observability profile 启动 db + backend + prometheus + grafana
#       (不启动 web: 它与演示栈共用 127.0.0.1:8088)
#    2) 导入 fixture.sql (100 只宠物 / 2 条排班 / 压测账号 itload)
#    3) docker run grafana/k6 跑 api-mix.js, 默认 30 req/s 持续 3 分钟
#    4) 导出 summary-steady.json, 控制台输出 tee 到证据目录
#    5) 宿主机 curl 抓 Prometheus targets 存证
#
#  环境变量:
#    RATE          默认 30, 每秒请求数
#    DURATION      默认 3m
#    PRE_VUS       默认 30, preAllocatedVUs
#    KEEP          默认 1, 保留栈供截图; KEEP=0 结束时自动 down -v
#    EVIDENCE_DIR  默认 docs/test-evidence/<运行日期>-observability, 可覆盖
#    YUZHUA_PROM_PORT / YUZHUA_GRAFANA_PORT
#                  宿主机端口, 只绑 127.0.0.1; 同一变量既控制 compose
#                  端口映射, 也用于脚本抓取 targets 和打印地址
#
#  Windows Git Bash: 挂载目录用 cygpath -m 转成 Windows 路径,
#  容器内路径通过 MSYS_NO_PATHCONV=1 禁止被 MSYS 改写。
# =====================================================================
set -euo pipefail

PROJECT=yuzhuaperf
NETWORK="${PROJECT}_default"
BASE_URL=http://backend:8081/api

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOAD_DIR="${ROOT_DIR}/tools/load"
# 证据按运行日期归档 (AGENTS.md 规矩二), 复跑不写回旧轮次目录
EVIDENCE_DIR="${EVIDENCE_DIR:-${ROOT_DIR}/docs/test-evidence/$(date +%Y%m%d)-observability}"

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

RATE="${RATE:-30}"
DURATION="${DURATION:-3m}"
PRE_VUS="${PRE_VUS:-30}"

cd "$ROOT_DIR"
mkdir -p "$EVIDENCE_DIR"

# 干净 checkout 没有根 .env 时, compose.yaml 的 :? 必填变量会让 preflight 直接失败。
# 压测栈是隔离的, 这里提供测试专用默认值; 外部已设置同名变量时优先使用外部值。
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
  if [[ "${KEEP:-1}" == "1" ]]; then
    echo "KEEP=1: 保留 ${PROJECT} 的容器与卷供截图"
    echo "  Grafana:    http://127.0.0.1:${GRAFANA_HOST_PORT:-${YUZHUA_GRAFANA_PORT:-3000}}"
    echo "  Prometheus: http://127.0.0.1:${PROM_HOST_PORT:-${YUZHUA_PROM_PORT:-9090}}/targets"
    echo "  清理: docker compose -p ${PROJECT} --profile observability down -v"
  else
    echo "清理 ${PROJECT} 的容器与卷..."
    docker compose -p "$PROJECT" --profile observability down -v --remove-orphans || true
  fi
  exit "$rc"
}
trap cleanup EXIT

# 确认 compose 解析出的网络名与 k6 容器使用的网络一致
if ! docker compose -p "$PROJECT" config 2>/dev/null | grep -q "name: ${NETWORK}"; then
  echo "错误: compose 网络名不是 ${NETWORK}, 请先检查 docker compose -p ${PROJECT} config" >&2
  exit 1
fi

echo "==> 启动 db + backend + prometheus + grafana (项目 ${PROJECT}, profile observability)"
# 不启动 web: 它与演示栈共用 127.0.0.1:8088; 压测流量只经过 backend。
# --wait-timeout 420: 首次 --build backend 叠加 db 初始化, 比 run-load.sh 的 300s 留更多余量。
docker compose -p "$PROJECT" --profile observability up -d --build --wait --wait-timeout 420 \
  db backend prometheus grafana

# .env 里的端口覆盖不会自动进入当前 shell；以 compose 实际发布的端口为准
PROM_HOST_PORT="$(docker compose -p "$PROJECT" port prometheus 9090 2>/dev/null | sed 's/.*://' | tr -d '\r' || true)"
PROM_HOST_PORT="${PROM_HOST_PORT:-${YUZHUA_PROM_PORT:-9090}}"
GRAFANA_HOST_PORT="$(docker compose -p "$PROJECT" port grafana 3000 2>/dev/null | sed 's/.*://' | tr -d '\r' || true)"
GRAFANA_HOST_PORT="${GRAFANA_HOST_PORT:-${YUZHUA_GRAFANA_PORT:-3000}}"

echo "==> 导入 fixture.sql"
docker compose -p "$PROJECT" exec -T db \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot yuzhua' \
  < "${LOAD_DIR}/fixture.sql" 2>&1 | tee "${EVIDENCE_DIR}/fixture.log"
# mysql 成功导入时没有输出, 补一行标记, 避免 fixture.log 为空无法核对
echo "fixture.sql 导入完成: schedule 9001=100 号, 9002=20 号, pet 90001..90100" \
  | tee -a "${EVIDENCE_DIR}/fixture.log"

echo "==> 稳态压测: RATE=${RATE} DURATION=${DURATION} PRE_VUS=${PRE_VUS}"
echo "Grafana 面板: http://127.0.0.1:${GRAFANA_HOST_PORT} (压测结束后选择覆盖 3 分钟的窗口)"

SUMMARY_NAME="summary-steady.json"
# 先删旧汇总与旧日志: 本次 k6 若在写文件前失败, 不能把上一次的 summary 当成本次证据
rm -f "${LOAD_DIR}/${SUMMARY_NAME}" "${EVIDENCE_DIR}/${SUMMARY_NAME}" \
  "${EVIDENCE_DIR}/steady.log" "${EVIDENCE_DIR}/prometheus-targets.json"
# 参数写在日志首行, 与 k6 输出同文件, 保证证据和实际参数对得上
echo "k6 steady: RATE=${RATE} DURATION=${DURATION} PRE_VUS=${PRE_VUS}" \
  | tee "${EVIDENCE_DIR}/steady.log"

k6_rc=0
set +e
MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
  -v "${LOAD_DIR_DOCKER}:/scripts" \
  -e BASE_URL="$BASE_URL" \
  -e RATE="$RATE" \
  -e DURATION="$DURATION" \
  -e PRE_VUS="$PRE_VUS" \
  grafana/k6 run "--summary-export=/scripts/${SUMMARY_NAME}" /scripts/api-mix.js \
  2>&1 | tee -a "${EVIDENCE_DIR}/steady.log"
k6_rc=${PIPESTATUS[0]}
set -e
if [[ -f "${LOAD_DIR}/${SUMMARY_NAME}" ]]; then
  cp -f "${LOAD_DIR}/${SUMMARY_NAME}" "${EVIDENCE_DIR}/${SUMMARY_NAME}"
  if ! strip_setup_data "${EVIDENCE_DIR}/${SUMMARY_NAME}"; then
    echo "错误: 无法从 ${SUMMARY_NAME} 删除 setup_data，证据可能带 token" >&2
    exit 1
  fi
  # k6 把汇总写在挂载目录里; 复制到证据目录后立刻删掉, 避免临时文件被误提交
  rm -f "${LOAD_DIR}/${SUMMARY_NAME}"
fi

echo "==> 抓取 Prometheus targets"
targets_rc=0
set +e
# 宿主机端口以 compose 实际发布为准, .env 里的覆盖也能生效
curl -fsS "http://127.0.0.1:${PROM_HOST_PORT}/api/v1/targets" \
  > "${EVIDENCE_DIR}/prometheus-targets.json"
targets_rc=$?
set -e
if [[ "$targets_rc" -ne 0 ]]; then
  echo "错误: 抓取 http://127.0.0.1:${PROM_HOST_PORT}/api/v1/targets 失败" >&2
else
  # HTTP 200 不代表抓取健康: target 可能是 down, 这样稳态数字没有服务端指标佐证
  if ! node -e '
const fs = require("fs");
const data = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
const up = (data.data && data.data.activeTargets || []).some(
  (t) => t.labels && t.labels.job === "yuzhua-backend" && t.health === "up");
process.exit(up ? 0 : 1);
' "${EVIDENCE_DIR}/prometheus-targets.json"; then
    echo "错误: Prometheus 没有健康的 yuzhua-backend target" >&2
    targets_rc=1
  fi
fi

final_rc=0
if [[ "$k6_rc" -ne 0 ]]; then
  echo "错误: 稳态压测未通过 (k6 exit ${k6_rc})" >&2
  final_rc=$k6_rc
elif [[ "$targets_rc" -ne 0 ]]; then
  final_rc=1
fi

if [[ "$final_rc" -eq 0 ]]; then
  echo "稳态压测通过: summary=${EVIDENCE_DIR}/${SUMMARY_NAME}"
else
  echo "稳态压测失败, 详见 ${EVIDENCE_DIR}" >&2
fi
exit "$final_rc"
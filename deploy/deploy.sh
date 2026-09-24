#!/usr/bin/env bash
#
# 生产部署脚本。
#
# 用法：
#   ./deploy/deploy.sh              # 构建镜像并滚动更新
#   ./deploy/deploy.sh --no-build   # 只用现有镜像重启（回滚或改配置时用）
#   ./deploy/deploy.sh --logs       # 查看应用日志
#   ./deploy/deploy.sh --status     # 查看服务状态与健康检查
#
# 设计原则：脚本遇到任何错误立即退出（set -e），避免"一半成功"的状态 ——
# 例如镜像构建失败但容器已被停止，那会让服务在无人察觉的情况下下线。

set -euo pipefail

# 以脚本所在位置为基准定位项目根目录，而不是依赖调用时的当前目录。
# 否则从其他目录执行 ./deploy/deploy.sh 会因为找不到 compose 文件而失败。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

COMPOSE_FILE="$SCRIPT_DIR/compose.prod.yaml"
ENV_FILE="$SCRIPT_DIR/.env"

log()  { printf '\033[0;32m[%s]\033[0m %s\n' "$(date '+%H:%M:%S')" "$*"; }
warn() { printf '\033[0;33m[警告]\033[0m %s\n' "$*"; }
die()  { printf '\033[0;31m[错误]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 前置检查

require_env_file() {
    if [[ ! -f "$ENV_FILE" ]]; then
        die "缺少 $ENV_FILE。请先执行：cp deploy/.env.example deploy/.env 并填写真实值"
    fi

    # 检查是否仍在用模板占位值。带着 CHANGE_ME 的密码上线，
    # 比启动失败更危险 —— 它是一个公开可知的凭证。
    if grep -qE '^[A-Z_]+=CHANGE_ME' "$ENV_FILE"; then
        warn "检测到 $ENV_FILE 中仍有 CHANGE_ME 占位值："
        grep -E '^[A-Z_]+=CHANGE_ME' "$ENV_FILE" | sed 's/=.*/=***/' | sed 's/^/  /'
        read -rp "仍然继续？(y/N) " answer
        [[ "$answer" =~ ^[Yy]$ ]] || die "已取消"
    fi
}

require_docker() {
    command -v docker >/dev/null 2>&1 || die "未找到 docker 命令"
    docker info >/dev/null 2>&1 || die "Docker daemon 未运行。若使用代理，请先确认代理可用（Docker 的拉取会经过它）"
    docker compose version >/dev/null 2>&1 || die "未找到 docker compose 插件"
}

compose() {
    docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

# ---------------------------------------------------------------- 动作

wait_for_healthy() {
    local service="$1" timeout="${2:-180}" elapsed=0

    log "等待 $service 通过健康检查（最多 ${timeout}s）..."
    while (( elapsed < timeout )); do
        local health
        health="$(compose ps --format '{{.Health}}' "$service" 2>/dev/null || echo '')"
        if [[ "$health" == "healthy" ]]; then
            log "$service 已就绪"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done

    warn "$service 在 ${timeout}s 内未通过健康检查，输出最近日志："
    compose logs --tail=50 "$service" || true
    die "部署失败：$service 不健康"
}

do_build() {
    log "构建镜像..."
    local image
    image="$(grep -E '^APP_IMAGE=' "$ENV_FILE" | cut -d= -f2- || echo 'shortlink-service:latest')"

    # 从项目根目录构建：构建上下文需要包含 pom.xml 与 src
    docker build -t "$image" "$PROJECT_ROOT"
    log "镜像构建完成: $image"
}

do_deploy() {
    log "启动/更新服务..."
    # --remove-orphans 清理 compose 文件中已删除的服务残留。
    # 不加这个参数时，重命名服务后旧容器会一直运行并占用端口，
    # 而 docker compose ps 不会显示它 —— 排查起来很费时间。
    compose up -d --remove-orphans

    wait_for_healthy mysql 180
    wait_for_healthy redis 60
    wait_for_healthy app 180

    log "部署完成"
    do_status
}

do_status() {
    echo
    log "服务状态："
    compose ps
    echo
    log "应用健康检查："
    if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health; then
        echo
    else
        warn "健康检查请求失败（若应用端口未绑定到本机，请在容器内检查）"
    fi
}

# ---------------------------------------------------------------- 入口

main() {
    case "${1:-}" in
        --logs)
            compose logs -f --tail=200 app
            ;;
        --status)
            do_status
            ;;
        --no-build)
            require_env_file
            require_docker
            do_deploy
            ;;
        --help|-h)
            sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            ;;
        "")
            require_env_file
            require_docker
            do_build
            do_deploy
            ;;
        *)
            die "未知参数: $1（用 --help 查看用法）"
            ;;
    esac
}

main "$@"

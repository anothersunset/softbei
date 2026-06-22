#!/bin/bash
# ============================================================
# notion-sync.sh - Notion → 本地 → 测试 → GitHub 自动化流程
# ============================================================
# 用法: ./notion-sync.sh <Notion页面ID> [目标文件路径]
#
# 功能:
#   1. 监听 Notion 页面变化
#   2. 自动同步到本地文件
#   3. 运行编译和测试
#   4. 测试通过后自动推送到 GitHub
# ============================================================

set -e

# 配置
NOTION_PAGE_ID=${1:-"38717a8a6c0281a8aadfe24221c15ced"}
TARGET_FILE=${2:-"src/main/java/com/zhiqian/ops/NewCode.java"}
CHECK_INTERVAL=${3:-10}
PROJECT_DIR="/c/Users/anoth/Documents/softbei/backend"
REPO_DIR="/c/Users/anoth/Documents/softbei"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 日志函数
log_info() { echo -e "${BLUE}ℹ️  $1${NC}"; }
log_success() { echo -e "${GREEN}✅ $1${NC}"; }
log_error() { echo -e "${RED}❌ $1${NC}"; }
log_warn() { echo -e "${YELLOW}⚠️  $1${NC}"; }

# 初始化
LAST_CONTENT=""
SYNC_COUNT=0

echo "=========================================="
echo "🚀 Notion → 本地 → 测试 → GitHub 自动化"
echo "=========================================="
echo ""
log_info "Notion 页面: $NOTION_PAGE_ID"
log_info "目标文件: $PROJECT_DIR/$TARGET_FILE"
log_info "检查间隔: ${CHECK_INTERVAL}秒"
log_info "GitHub 仓库: https://github.com/anothersunset/softbei.git"
echo ""
echo "按 Ctrl+C 停止"
echo "=========================================="
echo ""

# 主循环
while true; do
    # 1. 从 Notion 获取内容
    CURRENT_CONTENT=$(NOTION_KEYRING=0 ntn pages get "$NOTION_PAGE_ID" 2>/dev/null || echo "")

    # 检查是否获取成功
    if [ -z "$CURRENT_CONTENT" ]; then
        log_warn "无法获取 Notion 页面内容，等待重试..."
        sleep $CHECK_INTERVAL
        continue
    fi

    # 2. 检查是否有变化
    if [ "$CURRENT_CONTENT" != "$LAST_CONTENT" ]; then
        SYNC_COUNT=$((SYNC_COUNT + 1))
        echo ""
        echo "------------------------------------------"
        log_info "检测到变化！(第 ${SYNC_COUNT} 次同步)"
        echo "------------------------------------------"

        # 3. 同步到本地文件
        log_info "同步到本地文件..."
        FULL_PATH="$PROJECT_DIR/$TARGET_FILE"

        # 创建目录（如果不存在）
        mkdir -p "$(dirname "$FULL_PATH")"

        # 保存文件
        echo "$CURRENT_CONTENT" > "$FULL_PATH"
        log_success "已保存到 $TARGET_FILE"

        # 4. 编译检查
        log_info "运行编译检查..."
        cd "$PROJECT_DIR"

        if mvn compile -q 2>/dev/null; then
            log_success "编译通过！"

            # 5. 运行测试
            log_info "运行测试..."
            if mvn test -q 2>/dev/null; then
                log_success "测试通过！"

                # 6. 推送到 GitHub
                log_info "推送到 GitHub..."
                cd "$REPO_DIR"

                # 添加所有更改
                git add -A

                # 检查是否有更改需要提交
                if git diff --cached --quiet; then
                    log_warn "没有新的更改需要提交"
                else
                    # 提交更改
                    git commit -m "sync: 从 Notion 自动同步 (第 ${SYNC_COUNT} 次)

来源: Notion AI
页面 ID: $NOTION_PAGE_ID
文件: $TARGET_FILE
时间: $(date '+%Y-%m-%d %H:%M:%S')"

                    # 推送到远程
                    if git push origin main 2>/dev/null; then
                        log_success "已推送到 GitHub！"
                        echo ""
                        echo "🔗 https://github.com/anothersunset/softbei"
                    else
                        log_error "推送失败，请检查网络或权限"
                    fi
                fi
            else
                log_error "测试失败！请检查代码"
                echo ""
                log_info "运行详细测试输出:"
                mvn test 2>&1 | tail -20
            fi
        else
            log_error "编译失败！请检查代码"
            echo ""
            log_info "运行详细编译输出:"
            mvn compile 2>&1 | tail -20
        fi

        # 更新上次内容
        LAST_CONTENT="$CURRENT_CONTENT"
        echo ""
        log_info "等待下一次变化..."
    fi

    # 等待
    sleep $CHECK_INTERVAL
done

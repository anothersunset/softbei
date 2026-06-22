#!/bin/bash
# ============================================================
# notion-once.sh - 单次同步：Notion → 本地 → 测试 → GitHub
# ============================================================
# 用法: ./notion-once.sh <Notion页面ID> [目标文件路径]
#
# 功能:
#   1. 从 Notion 获取内容
#   2. 提取代码围栏内容（优先 java）
#   3. 保存到本地
#   4. 编译测试
#   5. 测试通过后推送到 GitHub
# ============================================================

set -e

# 配置
NOTION_PAGE_ID=${1:-"38717a8a6c0281a8aadfe24221c15ced"}
TARGET_FILE=${2:-"src/main/java/com/zhiqian/ops/NewCode.java"}
SKIP_BUILD=${3:-"false"}  # 设置为 true 跳过编译和测试
PROJECT_DIR="/c/Users/anoth/Documents/softbei/backend"
REPO_DIR="/c/Users/anoth/Documents/softbei"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}ℹ️  $1${NC}"; }
log_success() { echo -e "${GREEN}✅ $1${NC}"; }
log_error() { echo -e "${RED}❌ $1${NC}"; }
log_warn() { echo -e "${YELLOW}⚠️  $1${NC}"; }

# 从 Markdown 中提取代码围栏内容
# 用法: extract_block "<原始内容>" "<语言>"   (语言为空表示任意语言的第一个围栏)
extract_block() {
    printf '%s\n' "$1" | awk -v want="$2" '
        BEGIN { inb=0; ok=0; finished=0 }
        finished==1 { next }
        /^[[:space:]]*```/ {
            if (inb==0) {
                lang=$0
                sub(/^[[:space:]]*```[[:space:]]*/, "", lang)
                split(lang, a, /[[:space:]]+/)
                lang=a[1]
                inb=1
                if (want=="" || tolower(lang)==tolower(want)) ok=1; else ok=0
                next
            } else {
                inb=0
                if (ok==1) finished=1
                ok=0
                next
            }
        }
        inb==1 && ok==1 { print }
    '
}

# 判断字符串是否为空白
is_blank() { [ -z "$(printf '%s' "$1" | tr -d '[:space:]')" ]; }

echo "=========================================="
echo "🚀 Notion → 本地 → 测试 → GitHub (单次)"
echo "=========================================="
echo ""

# 1. 从 Notion 获取内容
log_info "从 Notion 获取代码..."
CURRENT_CONTENT=$(NOTION_KEYRING=0 ntn pages get "$NOTION_PAGE_ID" 2>/dev/null)

if [ -z "$CURRENT_CONTENT" ]; then
    log_error "无法获取 Notion 页面内容"
    exit 1
fi

log_success "获取成功！"

# 2. 提取代码围栏内容（优先 java，其次任意围栏，最后回退整页）
log_info "提取代码（优先 java 代码围栏）..."
CODE_CONTENT="$(extract_block "$CURRENT_CONTENT" java)"
if is_blank "$CODE_CONTENT"; then
    CODE_CONTENT="$(extract_block "$CURRENT_CONTENT" "")"
fi
if is_blank "$CODE_CONTENT"; then
    log_warn "未检测到代码围栏，回退为整页原始内容（可能不是合法代码，请检查 Notion 页面是否包含代码块）"
    CODE_CONTENT="$CURRENT_CONTENT"
else
    log_success "已从代码围栏提取代码"
fi

# 3. 保存到本地文件
log_info "保存到本地文件..."
FULL_PATH="$PROJECT_DIR/$TARGET_FILE"
mkdir -p "$(dirname "$FULL_PATH")"
printf '%s\n' "$CODE_CONTENT" > "$FULL_PATH"
log_success "已保存到 $TARGET_FILE"

# 4. 编译检查（可选）
if [ "$SKIP_BUILD" != "true" ]; then
    log_info "运行编译检查..."
    cd "$PROJECT_DIR"

    if ! mvn compile -q 2>/dev/null; then
        log_error "编译失败！"
        mvn compile 2>&1 | tail -20
        exit 1
    fi
    log_success "编译通过！"

    # 5. 运行测试
    log_info "运行测试..."
    if ! mvn test -q 2>/dev/null; then
        log_error "测试失败！"
        mvn test 2>&1 | tail -20
        exit 1
    fi
    log_success "测试通过！"
else
    log_warn "跳过编译和测试（SKIP_BUILD=true）"
fi

# 6. 推送到 GitHub
log_info "推送到 GitHub..."
cd "$REPO_DIR"

git add -A

if git diff --cached --quiet; then
    log_info "没有新的更改需要提交"
else
    git commit -m "sync: 从 Notion 同步

来源: Notion AI
页面 ID: $NOTION_PAGE_ID
文件: $TARGET_FILE
时间: $(date '+%Y-%m-%d %H:%M:%S')"

    if git push origin main 2>/dev/null; then
        log_success "已推送到 GitHub！"
        echo ""
        echo "🔗 https://github.com/anothersunset/softbei"
    else
        log_error "推送失败"
        exit 1
    fi
fi

echo ""
echo "=========================================="
log_success "全部完成！"
echo "=========================================="

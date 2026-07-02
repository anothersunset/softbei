#!/bin/bash
# ============================================================
# notion-build.sh - Notion 代码包 → 本地拆分 → 执行 → 编译 PDF
# ============================================================
# 用于 Notion 页面中按「文件名 → 代码块」组织的多文件项目。
# 每个代码块第一行必须以 # file: <filename> 标注目标文件名。
# 文件附件（如 .tex）自动通过 API 下载。
#
# 用法:
#   ./notion-build.sh <notion-page-id> [work-dir]
#
# 示例:
#   ./notion-build.sh bf7627f719ad4e4b8dbe71bcfa7c40d2 /d/cxy
# ============================================================

set -e

export PATH="$PATH:/c/Users/anoth/scoop/shims"
NOTION_KEYRING=0

NOTION_PAGE_ID="${1:?用法: $0 <notion-page-id> [work-dir]}"
WORK_DIR="${2:-$(pwd)}"

mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
log_err()   { echo -e "${RED}[ERR]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }

# ─── Step 1: 获取页面 Markdown ──────────────────────────────
log_info "fetching Notion page: $NOTION_PAGE_ID"
RAW=$(NOTION_KEYRING=0 ntn pages get "$NOTION_PAGE_ID" 2>/dev/null)
if [ -z "$RAW" ]; then
    log_err "failed to fetch page"
    exit 1
fi
log_ok "page fetched ($(echo "$RAW" | wc -l) lines)"

# ─── Step 2: 拆分代码块为独立文件 ────────────────────────────
# 代码块格式: ```<lang>
#             # file: <filename>
#             <code>
#             ```
log_info "extracting code blocks..."

SAVED_COUNT=0
current_file=""
current_lang=""
in_block=0

while IFS= read -r line; do
    # 检测代码块起始
    if [[ "$line" =~ ^\`\`\` ]]; then
        if [ $in_block -eq 0 ]; then
            # 进入代码块
            lang=$(echo "$line" | sed 's/^```//; s/[[:space:]].*//')
            in_block=1
            current_file=""
            current_lang="$lang"
            block_content=""
        else
            # 退出代码块 — 保存
            in_block=0
            if [ -n "$current_file" ] && [ -n "$block_content" ]; then
                printf '%s\n' "$block_content" > "$current_file"
                log_ok "saved: $current_file"
                SAVED_COUNT=$((SAVED_COUNT + 1))
            fi
            current_file=""
            block_content=""
        fi
        continue
    fi

    if [ $in_block -eq 1 ]; then
        # 检查第一行是否包含 # file: <filename>
        if [ -z "$current_file" ]; then
            # 只取文件名（第一段非空字符），忽略后面的中文注释
            if [[ "$line" =~ ^#\ *file:\ *([^[:space:]]+) ]]; then
                current_file="${BASH_REMATCH[1]}"
            fi
        fi
        # 累积代码内容
        if [ -z "$block_content" ]; then
            block_content="$line"
        else
            block_content="$block_content"$'\n'"$line"
        fi
    fi
done <<< "$RAW"

log_info "extracted $SAVED_COUNT code files"

# ─── Step 2b: 自动修补常见问题 ──────────────────────────────
# plots.py / enhance.py 等文件中的 /data/ 路径 → ./
for f in plots.py mooring2.py enhance.py; do
    if [ -f "$f" ] && grep -q '/data/' "$f" 2>/dev/null; then
        sed -i "s|/data/|./|g" "$f"
        log_info "patched /data/ → ./ in $f"
    fi
done

# ─── Step 3: 下载文件附件（.tex 等） ─────────────────────────
log_info "checking for file attachments..."
ATTACHMENTS=$(MSYS_NO_PATHCONV=1 NOTION_KEYRING=0 ntn api "/v1/blocks/${NOTION_PAGE_ID}/children" page_size==50 2>/dev/null | \
    python -c "
import sys, json
try:
    data = json.load(sys.stdin)
    for b in data.get('results', []):
        if b.get('type') == 'file':
            name = b['file'].get('name', '')
            url = b['file']['file']['url']
            print(f'{name}\t{url}')
except: pass
" 2>/dev/null)

if [ -n "$ATTACHMENTS" ]; then
    while IFS=$'\t' read -r fname url; do
        if [ -n "$fname" ] && [ -n "$url" ]; then
            log_info "downloading attachment: $fname"
            if curl -s -o "$fname" "$url" 2>/dev/null; then
                log_ok "downloaded: $fname ($(wc -l < "$fname") lines)"
            else
                log_warn "failed to download: $fname"
            fi
        fi
    done <<< "$ATTACHMENTS"
fi

# ─── Step 4: 自动执行管线 ────────────────────────────────────
TEX_FILE=""
PY_FILES=()
for f in *.tex; do
    [ -f "$f" ] && TEX_FILE="$f" && break
done
for f in *.py; do
    [ -f "$f" ] && PY_FILES+=("$f")
done

EXIT_CODE=0

# 4a. 运行 Python（按文件名排序）
if [ ${#PY_FILES[@]} -gt 0 ]; then
    log_info "running ${#PY_FILES[@]} Python file(s)..."
    for py in "${PY_FILES[@]}"; do
        echo "--- $py ---"
        if python "$py" 2>&1; then
            log_ok "$py passed"
        else
            log_err "$py failed"
            EXIT_CODE=1
        fi
        echo ""
    done
fi

# 4b. 编译 LaTeX（连编两遍）
if [ -n "$TEX_FILE" ]; then
    log_info "compiling $TEX_FILE ..."

    # 修复常见问题：补充 \degree 定义
    if grep -q '\\degree' "$TEX_FILE" 2>/dev/null && ! grep -q 'newcommand{[[:space:]]*\\degree}' "$TEX_FILE" 2>/dev/null; then
        log_warn "adding \\degree macro to $TEX_FILE"
        if grep -q '\\usepackage{xcolor}' "$TEX_FILE" 2>/dev/null; then
            sed -i 's/\\usepackage{xcolor}/\\usepackage{xcolor}\n\\newcommand{\\degree}{\$^\\circ\$}/' "$TEX_FILE"
        else
            sed -i 's/\\begin{document}/\\newcommand{\\degree}{\$^\\circ\$}\n\\begin{document}/' "$TEX_FILE"
        fi
    fi

    if xelatex -interaction=nonstopmode "$TEX_FILE" > /dev/null 2>&1 && \
       xelatex -interaction=nonstopmode "$TEX_FILE" > /dev/null 2>&1; then
        pdf="${TEX_FILE%.tex}.pdf"
        if [ -f "$pdf" ]; then
            log_ok "PDF: $pdf ($(ls -lh "$pdf" | awk '{print $5}'), $(pdfinfo "$pdf" 2>/dev/null | grep Pages | awk '{print $2}' || echo "?") pages)"
        fi
    else
        log_err "xelatex failed"
        EXIT_CODE=1
    fi
fi

# ─── Step 5: 清理临时文件 ────────────────────────────────────
rm -f ./*.aux ./*.log ./*.out ./*.toc 2>/dev/null || true

echo ""
echo "=========================================="
if [ $EXIT_CODE -eq 0 ]; then
    log_ok "ALL DONE — $SAVED_COUNT files, ${#PY_FILES[@]} py, ${TEX_FILE:-no tex}"
else
    log_warn "DONE with errors — check logs above"
fi
echo "output: $WORK_DIR"
echo "=========================================="

exit $EXIT_CODE

#!/bin/bash
# notion-detect.sh - UserPromptSubmit hook：检测 Notion 关键词并注入上下文
# 读取 stdin 中的用户提示，检测 Notion 相关模式，输出 additionalContext

PROMPT=$(cat)

# 检测 Notion 页面链接
NOTION_URLS=$(echo "$PROMPT" | grep -oP 'https://app\.notion\.com/p/\S+' || true)
NOTION_PAGE_IDS=$(echo "$PROMPT" | grep -oP 'notion\.so/\S+/([a-f0-9]{32})' | grep -oP '[a-f0-9]{32}' || true)

# 检测 Notion 关键词
HAS_KEYWORD=0
echo "$PROMPT" | grep -qi "notion.*抓取\|notion.*同步\|notion.*fetch\|notion.*sync\|notion.*下载\|notion.*内容\|notion.*页面\|notion.*代码\|notion.*编译\|notion.*agent\|notion.*build" && HAS_KEYWORD=1

if [ -z "$NOTION_URLS" ] && [ -z "$NOTION_PAGE_IDS" ] && [ "$HAS_KEYWORD" -eq 0 ]; then
    # 没有 Notion 相关内容，不做任何事
    exit 0
fi

# 构建注入的上下文
echo '{"additionalContext":"**Notion 自动化规则**：检测到 Notion 相关内容。\
\n- 如果有 Notion 页面链接/ID，使用脚本抓取：`NOTION_KEYRING=0 ntn pages get <id>` 获取 Markdown，`MSYS_NO_PATHCONV=1 NOTION_KEYRING=0 ntn api \"/v1/blocks/<uuid>/children\" page_size==50` 获取附件。\
\n- 完整自动化：`bash ~/Documents/softbei/notion-build.sh <page-id> <output-dir>`\
\n- Windows Git Bash 注意：ntn api 前面必须加 `MSYS_NO_PATHCONV=1` 防止路径转换。\
\n- 代码块按 `# file: <name>` 拆分，文件附件通过 blocks API 下载 S3 URL。\
\n- 常见 TeX 修复：`\\degree` 未定义 → `\\newcommand{\\degree}{$^\\circ$}`。\
\n- 脚本位置：`D:/cxy/notion-build.sh`、`~/Documents/softbei/notion-build.sh`"}'

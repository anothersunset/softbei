package com.zhiqian.ops.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 安全重定向惯用写法不应被无条件元字符红线误杀（源自龙芯真机真实 DeepSeek 红队回放，
 * 20260707-133050 报告 SAFE-01/02/04/07/08、REVIEW-01/08 共 7 条均因命令里含
 * {@code 2>&1}/{@code 2>/dev/null} 被硬编码的 '>' 元字符红线一刀切 BLOCK，
 * 而真实 LLM 生成命令时这两种写法几乎必带——直接命中当前项目"招牌功能"的可用性）。
 * 真正危险的任意文件写重定向仍必须无条件拦截，不能因此开出绕过口子。
 */
class SafeRedirectIdiomTest {

    private static IntentRiskGuard guard;

    static {
        try {
            guard = new IntentRiskGuard(new RiskRuleLoader());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void fd_duplication_redirect_is_not_hard_blocked() {
        // 源自 SAFE-01 真实回放：strace ... 2>&1 | head -20
        RiskDecision d = guard.evaluate("strace -p 1234 -c -S time 2>&1 | head -20");
        assertNotEquals(RiskLevel.BLOCK, d.level(), "2>&1 是标准 stderr 合并写法，不应触发红线：" + d.reason());
    }

    @Test
    void devnull_discard_redirect_is_not_hard_blocked() {
        // 源自 SAFE-07 真实回放：cat 配置文件失败时静默降级
        RiskDecision d = guard.evaluate("cat /etc/sasl2/smtpd.conf 2>/dev/null");
        assertEquals(RiskLevel.READONLY, d.level(), "2>/dev/null 丢弃到黑洞，不写任何文件，应按 cat 只读放行");
    }

    @Test
    void devnull_with_space_is_also_exempted() {
        RiskDecision d = guard.evaluate("ls -l /tmp/x 2> /dev/null");
        assertNotEquals(RiskLevel.BLOCK, d.level());
    }

    @Test
    void semicolon_chained_command_with_devnull_is_segmented_not_hard_blocked() {
        // 源自 REVIEW-08 真实回放：ls ... 2>/dev/null; echo $?
        RiskDecision d = guard.evaluate("ls -l /tmp/b.log 2>/dev/null; echo $?");
        assertNotEquals(RiskLevel.BLOCK, d.level(),
                "剔除安全重定向后应正常走分段裁决，而非在第一步就被元字符红线拦死");
    }

    @Test
    void genuine_file_write_redirect_is_still_hard_blocked() {
        // 回归防护：真正的任意文件写重定向不能因为本次修复被放行
        RiskDecision d = guard.evaluate("echo hacked > /tmp/whatever.txt");
        assertEquals(RiskLevel.BLOCK, d.level(), "非 2>&1/2>/dev/null 的普通文件写重定向必须仍然无条件拦截");
    }

    @Test
    void critical_config_overwrite_via_redirect_is_still_blocked() {
        RiskDecision d = guard.evaluate("echo root::0:0::/:/bin/sh > /etc/passwd");
        assertEquals(RiskLevel.BLOCK, d.level());
    }
}

package com.zhiqian.ops.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 同形字混淆防御：用 Cyrillic 形近字母伪装的危险命令应被折叠回 ASCII 后命中红线 BLOCK，
 * 而正常只读命令归一化后仍为 READONLY（不产生误杀）。
 */
class HomoglyphGuardTest {

    private IntentRiskGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        guard = new IntentRiskGuard(new RiskRuleLoader());
    }

    @Test
    void cyrillic_homoglyph_chmod_777_root_is_normalized_and_blocked() {
        // "chmоd 777 /"：其中 'о' 为 Cyrillic U+043E，映射回 ASCII 'o' 后命中 chmod 777 / 红线
        String disguised = "chmоd 777 /";
        assertEquals(RiskLevel.BLOCK, guard.evaluate(disguised).level(),
                "Cyrillic 同形字应被折叠回 ASCII 后命中红线 BLOCK");
    }

    @Test
    void plain_readonly_still_readonly_after_normalization() {
        assertEquals(RiskLevel.READONLY, guard.evaluate("df -h").level());
    }
}

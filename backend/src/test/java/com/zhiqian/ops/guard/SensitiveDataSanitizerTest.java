package com.zhiqian.ops.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataSanitizerTest {

    @Test
    void masks_common_secret_shapes_from_execution_output() throws Exception {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());

        String out = sanitizer.sanitize("""
                password=plain-text
                token: abcdef
                jdbc:mysql://root:secret@127.0.0.1:3306/app
                """);

        assertTrue(out.contains("password=***"));
        assertTrue(out.contains("token=***"));
        assertTrue(out.contains("jdbc:***"));
        assertFalse(out.contains("plain-text"));
        assertFalse(out.contains("abcdef"));
        assertFalse(out.contains("root:secret"));
    }

    @Test
    void recursively_masks_nested_tool_outputs() throws Exception {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());

        Object out = sanitizer.sanitizeValue(Map.of(
                "stdout", "password=plain-text",
                "nested", List.of(Map.of("stderr", "token: abcdef"))));
        String rendered = String.valueOf(out);

        assertTrue(rendered.contains("password=***"));
        assertTrue(rendered.contains("token=***"));
        assertFalse(rendered.contains("plain-text"));
        assertFalse(rendered.contains("abcdef"));
    }
}

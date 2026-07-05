package com.zhiqian.ops.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /**
     * 对象级脱敏必须能穿透任意 POJO/record 字段——不能只靠调用方（如 OpsAuditService）
     * 额外做一次整串 JSON 正则脱敏兜底。这是 API 响应/MCP 工具结果这类没有该兜底的路径
     * 唯一的防线，一旦这里失守，POJO 字段里的秘密会原样序列化进 HTTP 响应而不被察觉。
     */
    record SecretPojo(String detail, java.util.List<String> tags) {}

    @Test
    void sanitizeValue_masks_secrets_inside_arbitrary_pojo_fields() throws Exception {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());

        Object direct = sanitizer.sanitizeValue(new SecretPojo("password=vm-secret-token", List.of("ok")));
        assertTrue(direct instanceof Map, "POJO 应被摊平为通用 Map 供后续脱敏/序列化");
        String rendered = String.valueOf(direct);
        assertTrue(rendered.contains("password=***"));
        assertFalse(rendered.contains("vm-secret-token"));

        // 嵌套在 Map/List 里的 POJO 同样必须被穿透
        Object nested = sanitizer.sanitizeValue(Map.of(
                "plan", new SecretPojo("token: abcdef", List.of()),
                "items", List.of(new SecretPojo("password=another-secret", List.of()))));
        String nestedRendered = String.valueOf(nested);
        assertTrue(nestedRendered.contains("token=***"));
        assertTrue(nestedRendered.contains("password=***"));
        assertFalse(nestedRendered.contains("abcdef"));
        assertFalse(nestedRendered.contains("another-secret"));
    }

    @Test
    void sanitizeValue_passes_through_primitives_and_null_unchanged() throws Exception {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());

        assertEquals(42, sanitizer.sanitizeValue(42));
        assertEquals(Boolean.TRUE, sanitizer.sanitizeValue(Boolean.TRUE));
        assertEquals(null, sanitizer.sanitizeValue(null));
    }
}

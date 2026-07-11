package com.zhiqian.ops.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏落盘不得"吃穿" JSON 结构（P2）：修复前 {@code writeLine} 先序列化整行为 JSON 字符串，
 * 再对该字符串跑脱敏正则；若秘密值恰好是字段内容的结尾（后面紧跟 JSON 语法字符、无自然
 * 空白分隔），"非空白贪婪匹配" {@code [^\s]+} 会一路吃穿闭合引号/逗号/后续字段，
 * 产出损坏且截断的 JSONL 行。修复后先对 Map 做字段级脱敏再序列化，正则只在单个字符串值
 * 内部替换，不会跨到 JSON 语法上。
 */
class TraceAuditJsonIntegrityTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void secret_at_end_of_instruction_does_not_corrupt_json_line() throws Exception {
        Path file = tempDir.resolve("trace.jsonl");
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());
        OpsAuditService audit = new OpsAuditService(file.toString(), sanitizer);

        // 秘密值就是整条指令的结尾——序列化后紧跟的是闭合引号+逗号+下一字段，中间没有任何空白，
        // 正是修复前会触发"贪婪匹配吃穿 JSON"的最坏情形。
        OpsTrace t = audit.newTrace("password=vm-secret-token");

        String jsonl = Files.readString(file);
        String[] lines = jsonl.split("\\R");
        assertEquals(1, lines.length, "应恰好写入一行 OPEN 记录");
        String line = lines[0];

        assertFalse(line.contains("vm-secret-token"), "秘密不得以明文落盘：" + line);
        assertTrue(line.contains("password=***"), "秘密应被替换为 password=***：" + line);

        // 核心断言：这一行仍是合法 JSON，且后续字段（startEpochMs）没有被贪婪匹配吃掉。
        var node = assertDoesNotThrow(() -> mapper.readTree(line),
                "脱敏后的行必须仍是合法 JSON，不能被吃穿截断：" + line);
        assertEquals(t.getTraceId(), node.path("traceId").asText());
        assertTrue(node.has("startEpochMs"), "startEpochMs 字段不应被吃穿丢失：" + line);
        assertEquals("OPEN", node.path("stage").asText());
    }

    @Test
    void secret_followed_by_more_text_still_redacted_and_valid() throws Exception {
        Path file = tempDir.resolve("trace2.jsonl");
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());
        OpsAuditService audit = new OpsAuditService(file.toString(), sanitizer);

        audit.newTrace("帮我查看系统状态，password=vm-secret-token 然后重启服务");

        String line = Files.readString(file).split("\\R")[0];
        assertFalse(line.contains("vm-secret-token"));
        var node = assertDoesNotThrow(() -> mapper.readTree(line));
        assertTrue(node.path("instruction").asText().contains("然后重启服务"),
                "秘密之后的正常文本不应被误伤：" + line);
    }
}

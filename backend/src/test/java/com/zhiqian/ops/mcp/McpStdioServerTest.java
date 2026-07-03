package com.zhiqian.ops.mcp;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * McpStdioServer stdio 传输层覆盖测试。
 * <p>
 * ⽤ spy() 包裹真实 McpDispatcher——error()/ok() 是它⾃⼰拼报⽂的逻辑，
 * 纯 mock 会让这部分被替换成空值，测出来的是假象。
 * 验证内容：请求/通知区分、解析错误 -32700、空⾏跳过、EOF 正常退出。
 */
class McpStdioServerTest {

    private McpDispatcher realDispatcher;
    private McpDispatcher spyDispatcher;
    private McpStdioServer server;

    @BeforeEach
    void setUp() {
        // ⽤空的 ToolRegistry 构造真实 dispatcher，再 spy 以便校验交互
        realDispatcher = new McpDispatcher(new ToolRegistry(List.of()));
        spyDispatcher = spy(realDispatcher);
        server = new McpStdioServer(spyDispatcher);
    }

    // ───────────────────────────────────────────────
    // 请求 / 通知区分
    // ───────────────────────────────────────────────

    @Test
    void request_returns_response() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "EOF 正常退出");
        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""), "响应应包含 jsonrpc 字段");
        assertTrue(json.contains("\"id\":1"), "响应应携带请求 id");
        assertTrue(json.contains("\"result\""), "ping 成功应返回 result");
        verify(spyDispatcher).handle(any());
    }

    @Test
    void notification_returns_no_output() throws Exception {
        // 通知没有 id → dispatcher.handle() 返回 null → serve() 不输出
        String input = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "EOF 正常退出");
        String output = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(output.isEmpty(), "通知不应有任何输出，实际输出: [" + output + "]");
        verify(spyDispatcher).handle(any());
    }

    // ───────────────────────────────────────────────
    // 解析错误 -32700：⾮ JSON ⾏
    // ───────────────────────────────────────────────

    @Test
    void malformed_json_returns_parse_error() throws Exception {
        String input = "这不是 JSON\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "解析错误后仍应继续，直到 EOF");
        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("-32700"), "解析错误应返回 JSON-RPC -32700");
        assertTrue(json.contains("JSON 解析失败"), "错误信息应说明 JSON 解析失败");
        // 因为解析失败在 serve() 内部 try-catch 处理，不会⾛ dispatcher.handle()
        verify(spyDispatcher, never()).handle(any());
    }

    // ───────────────────────────────────────────────
    // 空⾏跳过
    // ───────────────────────────────────────────────

    @Test
    void empty_lines_are_skipped() throws Exception {
        // 空⾏ + 有效请求 + 空⾏
        String input = "\n  \n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "EOF 正常退出");
        String output = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(output.contains("\"id\":2"), "空⾏被跳过后，有效请求应正常响应");
        verify(spyDispatcher).handle(any());
    }

    // ───────────────────────────────────────────────
    // EOF 正常退出
    // ───────────────────────────────────────────────

    @Test
    void eof_exits_with_code_0() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "EOF 应返回退出码 0");
        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"tools\""), "tools/list 应返回⼯具列表");
    }

    // ───────────────────────────────────────────────
    // 多条请求交替
    // ───────────────────────────────────────────────

    @Test
    void multiple_requests_all_responded() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n";
        var out = new ByteArrayOutputStream();
        int exit = server.serve(toIn(input), out);

        assertEquals(0, exit, "EOF 正常退出");
        String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
        assertEquals(3, lines.length, "3 条请求应产⽣ 3 ⾏响应");
        assertTrue(lines[0].contains("\"id\":1"), "第⼀条响应 id=1");
        assertTrue(lines[1].contains("\"id\":2"), "第⼆条响应 id=2");
        assertTrue(lines[2].contains("\"id\":3"), "第三条响应 id=3");
        verify(spyDispatcher, times(3)).handle(any());
    }

    // ───────────────────────────────────────────────
    // ⼯具：将 String → InputStream（每⾏⼀条请求）
    // ───────────────────────────────────────────────

    private static InputStream toIn(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}

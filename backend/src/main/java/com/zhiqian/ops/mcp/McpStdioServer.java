package com.zhiqian.ops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MCP 的 stdio 传输层：以「换行分隔的 JSON-RPC」在 stdin/stdout 上与主 Agent(客户端)通信。
 * 用于体现赛题「MCP 运维插件化」：可作为独立进程启动，主 Agent 通过 stdin 送入请求、
 * 从 stdout 读取响应，无需网络端口。业务逻辑复用 McpDispatcher，与 HTTP 传输完全一致。
 *
 * 协议：
 *   - 每行一个 JSON-RPC 对象；请求(有 id) 响应一行 JSON；通知(无 id) 不输出。
 *   - 无法解析的行返回 JSON-RPC 解析错误(-32700, id=null)。
 *   - 读到 EOF 则优雅退出。
 */
@Component
public class McpStdioServer {

    /** JSON 解析错误码(JSON-RPC 2.0)。 */
    private static final int PARSE_ERROR = -32700;

    private final McpDispatcher dispatcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpStdioServer(McpDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 主循环：逐行读取请求并写回响应，直到 EOF。
     * @return 退出码(0=正常 EOF 退出)。
     */
    @SuppressWarnings("unchecked")
    public int serve(InputStream in, OutputStream out) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintStream writer = new PrintStream(out, true, StandardCharsets.UTF_8);
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                Map<String, Object> req;
                try {
                    req = mapper.readValue(trimmed, Map.class);
                } catch (Exception parseEx) {
                    writeLine(writer, dispatcher.error(null, PARSE_ERROR, "JSON 解析失败：" + parseEx.getMessage()));
                    continue;
                }

                Map<String, Object> resp = dispatcher.handle(req);
                if (resp != null) {
                    // 通知(resp==null) 不输出；请求写回一行 JSON
                    writeLine(writer, resp);
                }
            }
        } catch (Exception e) {
            return 1;
        }
        return 0;
    }

    private void writeLine(PrintStream writer, Map<String, Object> obj) {
        try {
            writer.println(mapper.writeValueAsString(obj));
        } catch (Exception e) {
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"序列化响应失败\"}}");
        }
    }
}

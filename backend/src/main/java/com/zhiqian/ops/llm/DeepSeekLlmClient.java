package com.zhiqian.ops.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiqian.ops.mcp.McpToolSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 国产开源模型客户端（DeepSeek / Qwen3 等 OpenAI 兼容接口）。
 * 使用 JDK 内置 HttpClient，无额外第三方依赖。
 */
public class DeepSeekLlmClient implements LlmClient {
    private final LlmProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekLlmClient(LlmProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .build();
    }

    @Override
    public boolean isReal() { return true; }

    @Override
    public String providerName() { return props.getProvider(); }

    @Override
    public boolean supportsTools() { return true; }

    @Override
    public String chat(String prompt) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", 0.2);
            body.put("stream", false);
            // 推理型模型（如 mimo-v2.5-pro）需足够的输出 token 才能完整返回 JSON，过小会被截断导致解析失败
            body.put("max_tokens", 4096);
            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", "你是谨慎的 Linux 运维专家。只返回严格的 JSON，字段：summary, rootCauseHypothesis, confidence(0~1), steps[{command, purpose}]。不要输出 JSON 以外的任何内容。");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", prompt);

            JsonNode message = send(body);
            return message.path("content").asText("");
        } catch (Exception e) {
            throw new RuntimeException("调用大模型失败：" + e.getMessage(), e);
        }
    }

    /**
     * OpenAI 兼容 function calling：将 MCP 工具定义映射为 tools 数组，
     * 模型返回 tool_calls 时转为 ToolCall 列表，否则返回最终文本。
     */
    @Override
    public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("temperature", 0.2);
            body.put("stream", false);
            body.put("max_tokens", 4096);

            ArrayNode msgArr = body.putArray("messages");
            for (ChatMessage m : messages) {
                ObjectNode node = msgArr.addObject();
                node.put("role", m.role());
                node.put("content", m.content() == null ? "" : m.content());
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall tc : m.toolCalls()) {
                        ObjectNode call = calls.addObject();
                        call.put("id", tc.id());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", tc.name());
                        fn.put("arguments", tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
                    }
                }
                if (m.toolCallId() != null) {
                    node.put("tool_call_id", m.toolCallId());
                }
            }

            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolArr = body.putArray("tools");
                for (McpToolSpec spec : tools) {
                    ObjectNode t = toolArr.addObject();
                    t.put("type", "function");
                    ObjectNode fn = t.putObject("function");
                    fn.put("name", spec.name());
                    fn.put("description", spec.description());
                    fn.set("parameters", mapper.valueToTree(spec.inputSchema()));
                }
            }

            JsonNode message = send(body);
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && toolCalls.size() > 0) {
                List<ToolCall> calls = new ArrayList<>();
                for (JsonNode tc : toolCalls) {
                    calls.add(new ToolCall(
                            tc.path("id").asText(""),
                            tc.path("function").path("name").asText(""),
                            tc.path("function").path("arguments").asText("{}")));
                }
                return new ToolChatResult(message.path("content").asText(""), calls);
            }
            return ToolChatResult.text(message.path("content").asText(""));
        } catch (Exception e) {
            throw new RuntimeException("调用大模型（工具模式）失败：" + e.getMessage(), e);
        }
    }

    /** 发送 chat/completions 请求并返回 choices[0].message 节点。 */
    private JsonNode send(ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM 接口返回状态码 " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        return root.path("choices").path(0).path("message");
    }
}

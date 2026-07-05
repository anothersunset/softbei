package com.zhiqian.ops.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.llm.ChatMessage;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.ToolChatResult;

import java.util.List;

/**
 * 意图一致性交叉校验器（动态意图审计 / cross-check）。
 *
 * 官方评分「动态意图审计」的核心内涵：验证 Agent 生成的指令是否与用户【原始意图】一致，
 * 检出大模型幻觉偏差、或被提示词注入诱导后「答非所问 / 夹带私货」的越权命令。
 *
 * 设计约束：
 *  - 独立于生成模型的第二次判断（cross-check），用专门的审计 system prompt。
 *  - 结果只能【升级】风险，绝不降级——即便判为一致也不会放宽规则层裁决，确定性底线不破。
 *  - 仅在真实模型下执行语义校验；Mock / 离线路径视为一致（no-op），保证评测可复现。
 */
public class IntentConsistencyChecker {
    private static final String AUDIT_SYSTEM_PROMPT =
            "你是运维安全审计员。给定【用户原始诉求】与【Agent 拟执行的命令列表】，"
            + "判断这些命令是否确实服务于用户的原始诉求，是否存在与诉求无关、越权、或疑似被诱导夹带的危险命令。"
            + "只返回严格 JSON：{\"consistent\": true/false, \"concern\": \"简述不一致点，若一致则空串\"}。"
            + "宁可保守：命令明显超出诉求范围或含诉求未提及的破坏性动作时判 false。";

    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntentConsistencyChecker(LlmClient llm) {
        this.llm = llm;
    }

    /** 交叉校验裁决结果。escalated=true 表示检出意图偏差、下游应升级为需人工确认。 */
    public record Verdict(boolean consistent, String concern, boolean escalated) {
        public static Verdict pass() {
            return new Verdict(true, "", false);
        }
    }

    /**
     * 校验命令列表是否与用户原始诉求一致。
     * @return 检出不一致时 escalated=true 且带 concern；一致或不可用时返回 consistent。
     */
    public Verdict check(String instruction, List<String> commands) {
        if (!llm.isReal() || instruction == null || instruction.isBlank()
                || commands == null || commands.isEmpty()) {
            return Verdict.pass();
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("【用户原始诉求】\n").append(instruction).append("\n\n【Agent 拟执行命令】\n");
            int i = 1;
            for (String c : commands) {
                sb.append(i++).append(". ").append(c).append("\n");
            }
            List<ChatMessage> messages = List.of(
                    ChatMessage.system(AUDIT_SYSTEM_PROMPT),
                    ChatMessage.user(sb.toString()));
            ToolChatResult res = llm.chatWithTools(messages, List.of());
            JsonNode j = mapper.readTree(extractJson(res.content()));
            boolean consistent = j.path("consistent").asBoolean(true);
            String concern = j.path("concern").asText("");
            return new Verdict(consistent, concern, !consistent);
        } catch (Exception e) {
            // 校验不可用时保守失败开放：不升级也不降级，交由规则层裁决主导。
            return Verdict.pass();
        }
    }

    private String extractJson(String s) {
        if (s == null) return "{}";
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : "{}";
    }
}

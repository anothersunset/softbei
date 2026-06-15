package com.zhiqian.ops.web;

import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维 Agent REST 入口。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsAgentController {
    private final OpsPipeline pipeline;
    private final List<AgentTool> tools;

    public OpsAgentController(OpsPipeline pipeline, List<AgentTool> tools) {
        this.pipeline = pipeline;
        this.tools = tools;
    }

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest req) {
        return Result.ok(pipeline.chat(req));
    }

    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentTool t : tools) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            list.add(m);
        }
        return Result.ok(list);
    }
}

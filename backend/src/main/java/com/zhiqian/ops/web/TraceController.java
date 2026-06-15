package com.zhiqian.ops.web;

import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推理链路溯源查询入口。
 */
@RestController
@RequestMapping("/api/ops")
public class TraceController {
    private final OpsAuditService audit;

    public TraceController(OpsAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/trace/{traceId}")
    public Result<OpsTrace> trace(@PathVariable String traceId) {
        OpsTrace t = audit.get(traceId);
        if (t == null) {
            return Result.error(404, "未找到溯源记录：" + traceId);
        }
        return Result.ok(t);
    }

    @GetMapping("/traces")
    public Result<List<OpsTrace>> traces(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(audit.recent(limit));
    }
}

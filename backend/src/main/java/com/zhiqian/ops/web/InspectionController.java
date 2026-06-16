package com.zhiqian.ops.web;

import com.zhiqian.ops.common.Result;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.inspect.InspectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主动巡检入口：按需触发一次只读体检，返回健康评分与风险预警。
 */
@RestController
@RequestMapping("/api/ops")
public class InspectionController {
    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @GetMapping("/inspect")
    public Result<InspectionReport> inspectGet() {
        return Result.ok(inspectionService.inspect());
    }

    @PostMapping("/inspect")
    public Result<InspectionReport> inspectPost() {
        return Result.ok(inspectionService.inspect());
    }
}

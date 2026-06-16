package com.zhiqian.ops.inspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时主动巡检：默认关闭(ops.inspect.scheduled-enabled=false)，开启后周期性体检并落盘溯源，
 * 体现「主动闭环运维」而非被动响应。仅做只读体检，不触发任何变更。
 */
@Component
@ConditionalOnProperty(prefix = "ops.inspect", name = "scheduled-enabled", havingValue = "true")
public class InspectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(InspectionScheduler.class);
    private final InspectionService inspectionService;

    public InspectionScheduler(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @Scheduled(fixedDelayString = "${ops.inspect.interval-ms:300000}", initialDelayString = "${ops.inspect.interval-ms:300000}")
    public void scheduledInspect() {
        try {
            InspectionReport r = inspectionService.inspect();
            log.info("[主动巡检] 完成：score={} overall={} traceId={}", r.healthScore(), r.overall(), r.traceId());
        } catch (Exception e) {
            log.warn("[主动巡检] 异常：{}", e.getMessage());
        }
    }
}

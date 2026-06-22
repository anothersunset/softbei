package com.zhiqian.ops.retriever;

import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAG 增强：验证检索前的「双语同义词/别名扩展」能让英文/口语化查询也召回中文 runbook。
 */
public class RagRetrievalTest {

    private ContextRetriever newRetriever() throws Exception {
        OpsAuditService audit = new OpsAuditService("/tmp/rag-test-" + System.nanoTime() + ".jsonl");
        return new ContextRetriever(audit, new RiskRuleLoader());
    }

    @Test
    void expandTerms_addsBilingualSynonyms() {
        // 原始分词不含任何中文词
        assertFalse(ContextRetriever.terms("disk full").contains("磁盘"));
        // 扩展后应补充中文同义词
        Set<String> expanded = ContextRetriever.expandTerms("disk full");
        assertTrue(expanded.contains("磁盘"), "扩展后应包含中文「磁盘」");
        assertTrue(expanded.contains("空间"), "扩展后应包含中文「空间」");
        // 原查询词仍应保留
        assertTrue(expanded.contains("disk"));
    }

    @Test
    void expandTerms_noFalseTriggerForUnrelatedQuery() {
        Set<String> expanded = ContextRetriever.expandTerms("hello world foobar");
        assertFalse(expanded.contains("磁盘"));
        assertFalse(expanded.contains("内存"));
    }

    @Test
    void englishQuery_retrievesChineseDiskRunbook() throws Exception {
        ContextRetriever r = newRetriever();
        List<Evidence> ev = r.retrieve("disk full, no space left on device", 4, null);
        assertFalse(ev.isEmpty(), "英文查询应能召回依据");
        assertTrue(ev.stream().anyMatch(e -> "01-runbook-disk.md".equals(e.source())),
                "英文「disk full」应跨语言召回中文磁盘 runbook");
    }

    @Test
    void chineseQuery_stillWorks() throws Exception {
        ContextRetriever r = newRetriever();
        List<Evidence> ev = r.retrieve("磁盘满了怎么清理", 4, null);
        assertFalse(ev.isEmpty());
        assertTrue(ev.stream().anyMatch(e -> "01-runbook-disk.md".equals(e.source())));
    }

    @Test
    void blankOrNullQuery_returnsEmpty() throws Exception {
        ContextRetriever r = newRetriever();
        assertTrue(r.retrieve("", 4, null).isEmpty());
        assertTrue(r.retrieve(null, 4, null).isEmpty());
        assertTrue(r.retrieve("   ", 4, null).isEmpty());
    }
}

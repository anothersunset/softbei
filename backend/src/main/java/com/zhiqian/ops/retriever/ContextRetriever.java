package com.zhiqian.ops.retriever;

import com.zhiqian.ops.guard.GuardRules;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.trace.OpsAuditService;
import com.zhiqian.ops.trace.OpsTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量离线运维知识检索器：在「感知」与「推理」之间为模型提供可引用的依据，
 * 让 Agent 从「会操作」升级为「有证据地操作」。
 *
 * 知识来源：
 *  1) classpath:kb/*.md —— 麒麟 V11 / LoongArch 文档与常见故障 runbook；
 *  2) risk-rules.yaml —— 危险命令规则、关键路径、变更类命令（经 RiskRuleLoader 加载）；
 *  3) 近期 trace 历史 —— 同类问题的处置记录（经 OpsAuditService 提供）。
 *
 * 检索算法：关键词 + IDF 加权的 BM25-lite，支持中文二元（bigram）切分，纯本地、零外部依赖；
 * 检索前增加「双语同义词/别名扩展」层，使英文或口语化查询也能召回中文 runbook；
 * 离线或知识库为空时优雅降级为空结果，绝不抛出异常、不影响主链路。
 */
@Component
public class ContextRetriever {
    private static final Logger log = LoggerFactory.getLogger(ContextRetriever.class);

    /**
     * 运维术语双语同义词/别名组：查询命中组内任一表达时，则为查询补充组内全部同义表达。
     * 用于跨语言/口语化召回，例如英文 "disk full" 也能命中中文「磁盘满 / 空间不足」runbook。
     */
    private static final List<String[]> SYNONYM_GROUPS = List.of(
            new String[]{"disk", "disk full", "no space", "磁盘", "磁盘满", "磁盘空间", "空间不足", "空间"},
            new String[]{"cpu", "load", "high load", "高负载", "负载", "进程", "process"},
            new String[]{"port", "端口", "端口占用", "连接数", "network", "网络", "connection"},
            new String[]{"memory", "oom", "out of memory", "内存", "内存溢出", "内存不足", "内存泄漏"},
            new String[]{"log", "日志", "journal", "journalctl", "日志膨胀"}
    );

    private final OpsAuditService audit;
    private final List<Doc> staticDocs = new ArrayList<>();

    public ContextRetriever(OpsAuditService audit, RiskRuleLoader ruleLoader) {
        this.audit = audit;
        try {
            loadKnowledgeBase();
        } catch (Exception e) {
            log.warn("加载 kb/*.md 失败，知识检索将降级：{}", e.getMessage());
        }
        try {
            loadRuleDocs(ruleLoader.rules());
        } catch (Exception e) {
            log.warn("加载安全规则知识失败：{}", e.getMessage());
        }
        log.info("ContextRetriever 就绪：静态知识 {} 条", staticDocs.size());
    }

    /** 检索与查询最相关的依据，最多 topK 条；无命中、知识库为空或异常时返回空列表。 */
    public List<Evidence> retrieve(String query, int topK, String excludeTraceId) {
        try {
            if (query == null || query.isBlank()) return List.of();
            List<Doc> corpus = new ArrayList<>(staticDocs);
            corpus.addAll(historyDocs(excludeTraceId));
            if (corpus.isEmpty()) return List.of();

            Set<String> qterms = expandTerms(query);
            if (qterms.isEmpty()) return List.of();

            int n = corpus.size();
            Map<String, Integer> df = new HashMap<>();
            for (Doc d : corpus) {
                for (String t : qterms) {
                    if (d.termSet.contains(t)) df.merge(t, 1, Integer::sum);
                }
            }
            List<Scored> scored = new ArrayList<>();
            for (Doc d : corpus) {
                double score = 0.0;
                for (String t : qterms) {
                    if (d.termSet.contains(t)) {
                        int dfi = df.getOrDefault(t, 1);
                        score += Math.log(1.0 + (double) n / dfi);
                    }
                }
                if (score > 0) scored.add(new Scored(d, score));
            }
            scored.sort((a, b) -> Double.compare(b.score, a.score));
            List<Evidence> out = new ArrayList<>();
            int limit = Math.min(Math.max(topK, 0), scored.size());
            for (int i = 0; i < limit; i++) {
                Doc d = scored.get(i).doc;
                double rounded = Math.round(scored.get(i).score * 1000.0) / 1000.0;
                out.add(new Evidence(d.id, d.kind, d.source, d.title, snippet(d.text), rounded));
            }
            return out;
        } catch (Exception e) {
            log.warn("知识检索异常，降级为空：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 对查询分词后追加双语同义词扩展：若查询词集完整包含某同义组任一成员的分词，
     * 则把该组全部成员的分词并入查询词集，从而提升跨语言/口语化召回。
     */
    static Set<String> expandTerms(String query) {
        List<String> base = terms(query);
        Set<String> baseSet = new HashSet<>(base);
        Set<String> out = new LinkedHashSet<>(base);
        if (baseSet.isEmpty()) return out;
        for (String[] group : SYNONYM_GROUPS) {
            boolean hit = false;
            for (String member : group) {
                List<String> mt = terms(member);
                if (!mt.isEmpty() && baseSet.containsAll(mt)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                for (String member : group) out.addAll(terms(member));
            }
        }
        return out;
    }

    private List<Doc> historyDocs(String excludeTraceId) {
        List<Doc> out = new ArrayList<>();
        try {
            List<OpsTrace> recent = audit.recent(20);
            int idx = 0;
            for (OpsTrace t : recent) {
                if (t.getTraceId() != null && t.getTraceId().equals(excludeTraceId)) continue;
                String instr = t.getInstruction();
                if (instr == null || instr.isBlank()) continue;
                String status = t.getFinalStatus() == null ? "未知" : t.getFinalStatus();
                String text = instr + "。历史处置结论：" + status;
                out.add(new Doc("hist-" + (idx++), "history", "历史trace", trim(instr, 40), text));
            }
        } catch (Exception e) {
            log.debug("历史 trace 不可用：{}", e.getMessage());
        }
        return out;
    }

    private void loadKnowledgeBase() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:kb/*.md");
        } catch (Exception e) {
            resources = new Resource[0];
        }
        for (Resource r : resources) {
            String name = r.getFilename() == null ? "kb" : r.getFilename();
            String content;
            try (InputStream in = r.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            parseMarkdown(name, content);
        }
    }

    /** 按 ## 二级标题切分文档块，每块作为一条可检索知识。 */
    private void parseMarkdown(String source, String content) {
        String curTitle = source;
        StringBuilder buf = new StringBuilder();
        int seq = 0;
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (!buf.toString().isBlank()) {
                    staticDocs.add(new Doc(source + "#" + (seq++), "doc", source, curTitle, buf.toString()));
                }
                buf.setLength(0);
                curTitle = line.substring(3).trim();
            } else if (line.startsWith("# ")) {
                String fileTitle = line.substring(2).trim();
                if (curTitle.equals(source)) curTitle = fileTitle;
            } else {
                buf.append(line).append("\n");
            }
        }
        if (!buf.toString().isBlank()) {
            staticDocs.add(new Doc(source + "#" + (seq++), "doc", source, curTitle, buf.toString()));
        }
    }

    private void loadRuleDocs(GuardRules rules) {
        if (rules == null) return;
        List<String> blocked = new ArrayList<>();
        for (GuardRules.BlockedPattern bp : rules.getBlockedPatterns()) {
            blocked.add(bp.getPattern() + "（" + bp.getReason() + "）");
        }
        if (!blocked.isEmpty()) {
            staticDocs.add(new Doc("rule-block", "rule", "risk-rules.yaml", "高危阻断规则（BLOCK）",
                    "命中以下模式将被直接拒绝执行：" + String.join("；", blocked)));
        }
        if (!rules.getCriticalPaths().isEmpty()) {
            staticDocs.add(new Doc("rule-path", "rule", "risk-rules.yaml", "关键路径保护",
                    "对以下关键路径的变更需格外谨慎，触碰将升级为阻断或人工确认：" + String.join("、", rules.getCriticalPaths())));
        }
        if (!rules.getReviewBinaries().isEmpty()) {
            staticDocs.add(new Doc("rule-review", "rule", "risk-rules.yaml", "变更类命令（需人工确认 REVIEW）",
                    "以下命令属于变更类操作，需人工二次确认后才会以最小权限执行：" + String.join("、", rules.getReviewBinaries())));
        }
        if (!rules.getReadOnlyBinaries().isEmpty()) {
            staticDocs.add(new Doc("rule-ro", "rule", "risk-rules.yaml", "只读安全命令（SAFE）",
                    "以下只读命令可安全执行用于诊断：" + String.join("、", rules.getReadOnlyBinaries())));
        }
    }

    // ===== 文本处理 =====

    private static String snippet(String text) {
        String s = text.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private static String trim(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 中英文混合分词：英文/数字按词（长度>=2），中文按相邻二元组（bigram）。 */
    static List<String> terms(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String lower = s.toLowerCase();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            boolean isAscii = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            boolean isCjk = c >= 0x4E00 && c <= 0x9FFF;
            if (isAscii) {
                ascii.append(c);
            } else {
                if (ascii.length() >= 2) out.add(ascii.toString());
                ascii.setLength(0);
            }
            if (isCjk) {
                cjk.append(c);
            } else {
                addCjkGrams(cjk.toString(), out);
                cjk.setLength(0);
            }
        }
        return out;
    }

    private static void addCjkGrams(String run, List<String> out) {
        if (run.isEmpty()) return;
        if (run.length() == 1) {
            out.add(run);
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            out.add(run.substring(i, i + 2));
        }
    }

    // ===== 内部结构 =====

    private static final class Doc {
        final String id;
        final String kind;
        final String source;
        final String title;
        final String text;
        final Set<String> termSet;

        Doc(String id, String kind, String source, String title, String text) {
            this.id = id;
            this.kind = kind;
            this.source = source;
            this.title = title;
            this.text = text;
            this.termSet = new HashSet<>(terms(title + " " + text));
        }
    }

    private record Scored(Doc doc, double score) {}
}

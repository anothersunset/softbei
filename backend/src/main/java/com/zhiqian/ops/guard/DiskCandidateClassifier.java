package com.zhiqian.ops.guard;

import com.zhiqian.ops.agent.tools.FileCandidate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 磁盘清理候选文件的四档风险分类：只做路径/文件名模式匹配 + 进程占用检测，
 * 不做文件内容扫描（内容扫描属于 DLP 范畴，超出本场景合理成本）。
 * 未命中任何已知模式的文件默认落「需谨慎」而不是「建议清理」——agent 认不出的东西不自作主张。
 */
@Component
public class DiskCandidateClassifier {

    public enum Tier { BLOCKED, PRIVACY_SENSITIVE, CAUTION, RECOMMENDED }

    public record Classification(Tier tier, String reason) {}

    /** 已知安全的轮转/归档/缓存模式：命中且未被进程占用 → 建议清理（仍需人工确认才会真正执行）。 */
    private static final List<Pattern> SAFE_PATTERNS = List.of(
            Pattern.compile(".*\\.log\\.\\d+$"),
            Pattern.compile(".*\\.log\\.gz$"),
            Pattern.compile(".*\\.old$"),
            Pattern.compile("^/var/cache/.*"),
            Pattern.compile("^/tmp/.*")
    );

    /** 凭据/隐私类文件名模式：保留在候选表中供人工识别，不是禁止。 */
    private static final List<Pattern> PRIVACY_PATTERNS = List.of(
            Pattern.compile(".*\\.pem$"),
            Pattern.compile(".*\\.key$"),
            Pattern.compile(".*id_rsa.*"),
            Pattern.compile(".*\\.env$"),
            Pattern.compile("(?i).*(secret|password|token).*"),
            Pattern.compile("^/home/.*")
    );

    private final RiskRuleLoader ruleLoader;

    public DiskCandidateClassifier(RiskRuleLoader ruleLoader) {
        this.ruleLoader = ruleLoader;
    }

    /**
     * @param heldOpen 该文件当前是否被某进程持有句柄（lsof/fuser 检测结果），
     *                 持有中视为「仍在写入」，一律红线禁选，与路径模式无关。
     */
    public Classification classify(FileCandidate file, boolean heldOpen) {
        String path = file.path();
        if (heldOpen) {
            return new Classification(Tier.BLOCKED, "文件正被进程占用，可能仍在写入，禁止操作");
        }
        String matchedCriticalPath = matchCriticalPath(path);
        if (matchedCriticalPath != null) {
            return new Classification(Tier.BLOCKED, "位于系统关键路径 " + matchedCriticalPath + " 下，禁止操作");
        }
        for (Pattern p : PRIVACY_PATTERNS) {
            if (p.matcher(path).matches()) {
                return new Classification(Tier.PRIVACY_SENSITIVE, "文件名匹配凭据/隐私敏感模式，可能包含个人数据，删除不可撤销");
            }
        }
        for (Pattern p : SAFE_PATTERNS) {
            if (p.matcher(path).matches()) {
                return new Classification(Tier.RECOMMENDED, "匹配已知安全的轮转/归档/缓存模式");
            }
        }
        return new Classification(Tier.CAUTION, "未识别文件用途，建议先确认再操作");
    }

    /** 复用 risk-rules.yaml 现有 criticalPaths，不新建列表；"/" 本身跳过（否则会匹配一切绝对路径）。 */
    private String matchCriticalPath(String path) {
        List<String> criticalPaths = ruleLoader.rules().getCriticalPaths();
        for (String cp : criticalPaths) {
            if ("/".equals(cp)) {
                continue;
            }
            String prefix = cp.endsWith("/") ? cp : cp + "/";
            if (path.equals(cp) || path.startsWith(prefix)) {
                return cp;
            }
        }
        return null;
    }
}

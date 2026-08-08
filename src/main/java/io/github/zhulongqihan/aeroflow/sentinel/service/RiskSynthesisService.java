package io.github.zhulongqihan.aeroflow.sentinel.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.ContextPack;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.Evidence;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskFinding;
import io.github.zhulongqihan.aeroflow.sentinel.agent.runtime.RiskHypothesis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化风险归因适配器。
 *
 * Demo 默认走规则 fallback；打开 feature flag 后才调用 DashScope，并且只有通过
 * JSON Schema 语义校验的模型结果才会进入 Runtime 状态。
 */
@Service
public class RiskSynthesisService {

    private static final Logger logger = LoggerFactory.getLogger(RiskSynthesisService.class);
    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final String apiKey;

    public RiskSynthesisService(
            ChatService chatService,
            @Value("${agent.v2.model-synthesis-enabled:false}") boolean enabled,
            @Value("${spring.ai.dashscope.api-key:}") String apiKey) {
        this.chatService = chatService;
        this.enabled = enabled;
        this.apiKey = apiKey;
    }

    public List<RiskFinding> synthesize(
            ContextPack contextPack,
            List<Evidence> evidence,
            List<RiskHypothesis> hypotheses,
            List<RiskFinding> fallback) {
        if (!enabled || apiKey == null || apiKey.isBlank() || apiKey.contains("your-api-key")) {
            return fallback;
        }

        try {
            DashScopeApi api = chatService.createDashScopeApi();
            DashScopeChatModel model = chatService.createChatModel(api, 0.1, 1600, 0.8);
            String raw = model.call(new Prompt(buildPrompt(contextPack, evidence, hypotheses)))
                    .getResult().getOutput().getText();
            RiskSynthesisEnvelope envelope = objectMapper.readValue(extractJson(raw), RiskSynthesisEnvelope.class);
            validate(envelope, evidence);
            return envelope.findings();
        } catch (Exception exception) {
            logger.warn("结构化模型归因失败，使用规则 fallback: {}", exception.getMessage());
            return fallback;
        }
    }

    private void validate(RiskSynthesisEnvelope envelope, List<Evidence> evidence) {
        if (envelope == null || envelope.findings() == null || envelope.findings().isEmpty()) {
            throw new IllegalArgumentException("模型未返回 findings");
        }
        Set<String> availableEvidence = evidence.stream()
                .filter(item -> "SUCCESS".equalsIgnoreCase(item.status()))
                .map(Evidence::id)
                .collect(HashSet::new, Set::add, Set::addAll);
        for (RiskFinding finding : envelope.findings()) {
            if (finding.id() == null || finding.summary() == null || finding.evidenceIds() == null
                    || finding.evidenceIds().isEmpty() || !availableEvidence.containsAll(finding.evidenceIds())) {
                throw new IllegalArgumentException("finding 未通过 Evidence 引用校验");
            }
        }
    }

    private String buildPrompt(ContextPack contextPack, List<Evidence> evidence, List<RiskHypothesis> hypotheses) {
        return """
                你是航旅稳定性风险归因器。只能基于给定 Evidence 输出 JSON，不得添加未出现的事实。
                输出格式必须是 {\"findings\":[{\"id\":\"...\",\"level\":\"HIGH|MEDIUM|LOW\",\"chain\":\"...\",\"summary\":\"...\",\"evidenceIds\":[\"...\"],\"confidence\":0.0,\"status\":\"VERIFIED|NEEDS_REVIEW\",\"recommendation\":\"...\"}]}。
                场景: %s, 航线: %s, 时间: %s, 严重级别: %s
                Context Pack: %s
                Hypotheses: %s
                Evidence: %s
                """.formatted(contextPack.scenario(), contextPack.route(), contextPack.timeRange(),
                contextPack.severityHint(), contextPack.incidentSummary(), hypotheses, evidence);
    }

    private String extractJson(String raw) {
        if (raw == null) throw new IllegalArgumentException("模型返回为空");
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("模型未返回 JSON");
        return raw.substring(start, end + 1);
    }

    public record RiskSynthesisEnvelope(List<RiskFinding> findings) {
    }
}

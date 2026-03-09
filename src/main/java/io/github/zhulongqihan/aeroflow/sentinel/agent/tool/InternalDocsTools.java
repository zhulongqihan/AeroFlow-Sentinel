package io.github.zhulongqihan.aeroflow.sentinel.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhulongqihan.aeroflow.sentinel.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;

    @Value("${knowledge.base.path:./aiops-docs}")
    private String knowledgeBasePath;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(@Autowired(required = false) VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        

        try {
            List<VectorSearchService.SearchResult> searchResults;

            if (vectorSearchService != null) {
                // 使用向量搜索服务检索相关文档
                searchResults = vectorSearchService.searchSimilarDocuments(query, topK);
            } else {
                logger.warn("Milvus 未启用，queryInternalDocs 使用本地文档兜底检索: {}", knowledgeBasePath);
                searchResults = searchLocalDocuments(query, topK);
            }
            
            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            
            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(searchResults);
            

            return resultJson;
            
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }

    private List<VectorSearchService.SearchResult> searchLocalDocuments(String query, int limit) throws IOException {
        Path docsPath = Paths.get(knowledgeBasePath).normalize();
        if (!Files.exists(docsPath) || !Files.isDirectory(docsPath)) {
            logger.warn("本地知识库目录不存在: {}", docsPath);
            return List.of();
        }

        List<String> keywords = Stream.of(query.toLowerCase(Locale.ROOT).split("\\s+|,|，|。|；|;|\\|"))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .toList();

        List<VectorSearchService.SearchResult> results = new ArrayList<>();
        try (Stream<Path> pathStream = Files.list(docsPath)) {
            pathStream
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> addLocalSearchResult(path, keywords, results));
        }

        return results.stream()
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getScore).reversed())
                .limit(limit)
                .toList();
    }

    private void addLocalSearchResult(Path path, List<String> keywords, List<VectorSearchService.SearchResult> results) {
        try {
            String content = Files.readString(path);
            String lowerContent = content.toLowerCase(Locale.ROOT);
            long matchedKeywords = keywords.stream().filter(lowerContent::contains).count();

            if (matchedKeywords == 0 && !keywords.isEmpty()) {
                return;
            }

            VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
            result.setId(path.getFileName().toString());
            result.setContent(extractSnippet(content, keywords));
            result.setScore(keywords.isEmpty() ? 0.1f : (float) matchedKeywords / keywords.size());
            result.setMetadata(Map.of("source", path.toString(), "mode", "local-fallback").toString());
            results.add(result);
        } catch (IOException e) {
            logger.warn("读取本地知识文档失败: {}", path, e);
        }
    }

    private String extractSnippet(String content, List<String> keywords) {
        if (keywords.isEmpty()) {
            return content.substring(0, Math.min(content.length(), 600));
        }

        String lowerContent = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int index = lowerContent.indexOf(keyword);
            if (index >= 0) {
                int start = Math.max(0, index - 120);
                int end = Math.min(content.length(), index + 480);
                return content.substring(start, end);
            }
        }

        return content.substring(0, Math.min(content.length(), 600));
    }
}

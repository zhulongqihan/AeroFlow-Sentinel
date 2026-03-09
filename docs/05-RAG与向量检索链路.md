# RAG 与向量检索链路

## 文档目标

这份文档说明知识文档从上传到被检索利用的完整流程。读完后应该能回答：

1. 文档是如何被切片和向量化的
2. Milvus 里的存储结构是什么
3. 查询时如何从检索走到回答生成
4. 轻量模式下为什么还能保留知识增强能力

## 关键文件

1. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/DocumentChunkService.java
2. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/VectorEmbeddingService.java
3. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/VectorIndexService.java
4. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/VectorSearchService.java
5. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/service/RagService.java
6. src/main/java/io/github/zhulongqihan/aeroflow/sentinel/agent/tool/InternalDocsTools.java

## 完整数据流

```text
上传文档
  -> FileUploadController
  -> VectorIndexService.indexSingleFile()
  -> DocumentChunkService 分片
  -> VectorEmbeddingService 向量化
  -> Milvus 存储

用户提问
  -> VectorSearchService 搜索相似片段
  -> RagService 或 InternalDocsTools 组装上下文
  -> 模型生成基于证据的回答
```

## 文档分片策略

DocumentChunkService 不会直接粗暴按固定长度切文本，而是优先保留语义边界：

1. 先按 Markdown 标题切章节。
2. 再按段落切分内容。
3. 如果章节仍过长，再按大小继续切。
4. 相邻分片之间保留重叠内容。

默认参数是：

1. max-size=800
2. overlap=100

这样做的原因是：

1. 保留语义完整性。
2. 减少跨段落信息丢失。
3. 提高后续检索命中质量。

## 向量化流程

VectorEmbeddingService 使用 DashScope Embedding 模型生成向量。当前关键点是：

1. 使用 text-embedding-v4。
2. 生成 1024 维向量。
3. 同时支持单条文本和批量文本向量化。

## Milvus 中的存储结构

Milvus 集合里核心字段包括：

1. id
2. vector
3. content
4. metadata

其中 metadata 会存储：

1. 文档来源路径
2. 文件扩展名
3. 文件名
4. chunkIndex
5. totalChunks
6. title，若存在

这让检索结果不仅能返回内容，还能回溯文档来源和上下文位置。

## 向量检索流程

VectorSearchService 的核心步骤是：

1. 把用户问题向量化。
2. 在 Milvus 中按 TopK 搜索相似向量。
3. 取回内容和 metadata。
4. 返回给 RagService 或 InternalDocsTools 使用。

## RagService 的角色

RagService 负责把“检索到的证据”变成“可用的提示词上下文”。它会：

1. 收集检索结果。
2. 组装参考资料上下文。
3. 把上下文和用户问题一起交给模型。
4. 以流式方式返回最终回答。

## 轻量模式的 fallback

在 demo 模式下，Milvus 会关闭。这时：

1. Vector 相关服务不会创建 Bean。
2. InternalDocsTools 自动回退到本地 Markdown 检索。
3. RagService 会提示当前未启用向量检索能力。

这意味着轻量模式不会拥有完整向量检索质量，但仍保留“有知识依据地回答问题”的能力。

## 关键设计决策

1. 文档切片必须优先保留标题和段落边界。
2. metadata 不能只存来源路径，要能支持后续上下文定位。
3. 向量链路必须允许整体关闭，并有本地 fallback。

## 当前局限

1. 当前检索结果还缺少更细粒度的排序和重排能力。
2. RAG 结果没有独立的质量评估指标。
3. 轻量模式下的本地检索更像兜底，而不是完整替代。

## 简历表达建议

### 项目亮点总结

搭建从文档上传、语义切片、向量化、Milvus 存储到检索增强生成的完整 RAG 链路，并设计本地 Markdown fallback 方案以兼顾低配部署与知识增强能力。

### 面试常见追问

1. 为什么切片时要保留 overlap
2. metadata 为什么重要
3. 为什么不强制要求所有环境都启用 Milvus
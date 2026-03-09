# 配置体系与部署模式

## 文档目标

这份文档说明项目如何通过配置开关适配不同运行环境。读完后应该能回答：

1. application.yml 和 application-demo.yml 的职责区别
2. 项目有哪些关键开关
3. 当前有哪些运行模式
4. 为什么这种配置方式适合开源展示和低配部署

## 关键文件

1. src/main/resources/application.yml
2. src/main/resources/application-demo.yml
3. pom.xml
4. vector-database.yml
5. Makefile

## 两套配置文件的分工

### application.yml

标准模式配置，适合本地完整开发或接近真实环境的运行方式。默认特点是：

1. Milvus 开启。
2. MCP 客户端开启。
3. Prometheus 与 CLS 默认走真实模式。

### application-demo.yml

轻量演示模式配置，适合低配 ECS 或开源展示环境。默认特点是：

1. Milvus 关闭。
2. MCP 客户端关闭。
3. Prometheus 和 CLS 启用 mock 数据。
4. 会话持久化仍可用，但会写入单独的 demo 文件。

## 关键配置项

### 1. 文件上传

1. file.upload.path
2. file.upload.allowed-extensions

### 2. 会话持久化

1. chat.session.persistence-enabled
2. chat.session.persistence-path
3. chat.session.max-window-size

### 3. Milvus

1. milvus.enabled
2. milvus.host
3. milvus.port
4. milvus.timeout

### 4. 知识库路径

1. knowledge.base.path

### 5. DashScope

1. spring.ai.dashscope.api-key
2. spring.ai.dashscope.chat.options.timeout
3. dashscope.embedding.model

### 6. RAG

1. rag.top-k
2. rag.model

### 7. 监控与日志

1. prometheus.base-url
2. prometheus.mock-enabled
3. cls.mock-enabled

### 8. MCP

1. spring.ai.mcp.client.enabled
2. SSE 连接配置

## 三种典型运行模式

| 模式 | Milvus | MCP | 告警/日志 | 适用场景 |
|------|--------|-----|-----------|----------|
| 本地完整开发 | 开 | 开 | 真实或半真实 | 联调和完整体验 |
| 低配演示 | 关 | 关 | mock | ECS 演示和开源展示 |
| 半真实集成 | 开或关 | 开 | 混合 | 接真实平台逐步演进 |

## 构建与启动

### 打包

```bash
mvn clean package -DskipTests
```

### 运行 Demo 模式

```bash
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

### 可选完整模式

```bash
docker compose up -d -f vector-database.yml
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar
```

## 为什么这套配置设计重要

1. 它让项目能在不同资源条件下运行。
2. 它避免把“完整功能”和“能成功部署”绑死在一起。
3. 它给开源展示、求职演示和后续真实集成都预留了空间。
4. 它保证前端值班驾驶舱在 demo 模式下也能完整展示模式切换、会话持久化和典型演示场景。

## 当前局限

1. 配置项已比较多，后续可以考虑进一步分组或加配置说明文档。
2. 当前还没有正式的 secrets 管理方案。
3. 会话持久化当前是文件方案，适合单机场景，不适合多实例共享。
4. 前端驾驶舱展示的是会话级和演示级状态，不是完整运维监控大盘。

## 简历表达建议

### 项目亮点总结

通过配置开关将项目拆分为完整模式与轻量演示模式，支持在 Milvus、MCP、mock 风险证据和会话持久化之间灵活切换，兼顾工程能力展示与低资源部署落地。

### 面试常见追问

1. 为什么要保留 demo profile
2. 为什么会话持久化当前使用文件方案
3. 你如何平衡完整功能和部署成本
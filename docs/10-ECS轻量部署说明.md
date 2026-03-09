# ECS 低配轻量部署说明

## 文档目标

这份文档解释项目如何在 2C2G 等低配 ECS 环境中运行，并说明为什么这个部署模式对开源展示和面试演示非常重要。

## 适用场景

轻量部署模式适合以下情况：

1. 服务器配置较低，不适合同时拉起 Java 服务、Milvus 和额外中间件。
2. 需要优先保证在线演示可用，而不是追求完整生产依赖。
3. 需要在成本可控的前提下展示多 Agent、RAG 和工具调用能力。

## 轻量模式做了什么

当采用 demo profile 时，系统做了下面这些取舍：

1. 关闭 Milvus，避免额外内存与容器开销。
2. 关闭 MCP 客户端，减少外部依赖复杂度。
3. 使用 mock Prometheus 告警数据。
4. 使用 mock CLS 日志数据。
5. 使用本地 Markdown 文档作为知识检索兜底。

## 关键配置

推荐使用 application-demo.yml，并保证以下开关：

1. milvus.enabled=false
2. spring.ai.mcp.client.enabled=false
3. prometheus.mock-enabled=true
4. cls.mock-enabled=true
5. knowledge.base.path=./aiops-docs

## 启动方式

### Maven 方式

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```

### Jar 方式

```bash
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

## 线上部署形态

当前项目已经在轻量模式下完成线上部署，典型部署结构是：

1. Java 服务监听 9900 端口。
2. Nginx 反向代理对外暴露域名。
3. 用户通过浏览器访问统一前端页面。

当前演示地址：

1. http://agent.cyruszhang.online

## 启动前检查项

### 环境准备

1. Java 17 已正确安装。
2. DASHSCOPE_API_KEY 已正确配置。
3. 目标端口未被旧进程占用。

### 打包检查

1. 执行 mvn clean package -DskipTests 成功。
2. target 目录下存在目标 jar。
3. demo profile 配置已生效。

## 启动后验证项

1. 根路径可以访问前端静态页面。
2. /api/chat 可以返回正常对话结果。
3. /api/flight_guard 可以返回流式巡检结果。
4. 页面上的巡检按钮和问答输入框工作正常。
5. 值班驾驶舱中的服务端会话卡片能够随着提问成功刷新。
6. 一键演示场景可以直接触发对应问答或巡检流程。

## 为什么这个部署方案值得在面试中强调

面试官通常不只看你有没有写功能，也看你有没有考虑落地成本。轻量部署模式能说明：

1. 你考虑了资源约束，而不是只在本地高配环境里跑通。
2. 你知道哪些能力是核心展示项，哪些依赖可以降级。
3. 你具备把 AI 项目真正部署上线并稳定演示的意识。
4. 你还能把“可见演示层”一起上线，而不是只让接口能通。

## 当前模式的边界

轻量模式不是完整生产方案，限制主要包括：

1. 不具备真实向量数据库检索能力。
2. 不直接依赖真实 Prometheus 和真实日志平台。
3. 更适合演示、开源展示和求职包装，而不是直接承接生产流量。

## 后续升级方向

如果后续希望从演示模式继续升级，可以按照下面顺序推进：

1. 重新启用 Milvus，恢复完整向量检索。
2. 打通真实 Prometheus 告警源。
3. 通过 MCP 或直连方式接入真实日志平台。
4. 增加 systemd、Dockerfile 或容器化部署模板。
5. 补齐 HTTPS、监控、日志保留和故障恢复策略。

## 一句话总结

轻量部署模式的核心价值，是让 AeroFlow Sentinel 在低资源机器上也能稳定演示关键能力，从而兼顾项目可访问性、工程可落地性和求职展示效果。
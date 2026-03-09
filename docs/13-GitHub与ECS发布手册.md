# GitHub 同步与 ECS 发布手册

## 文档目标

这份文档专门记录这个项目当前可复用的发布动作，避免每次发布都重新摸索。它主要回答：

1. 本地改动如何同步到 GitHub
2. 线上 ECS 现在采用什么发布方式
3. 前端静态资源改动为什么也必须重新打包 jar
4. 发布后应该验证哪些结果

## 当前发布现实

这个项目当前不是在服务器上直接 `git pull` 运行，而是采用下面的方式：

1. 本地开发并打包。
2. GitHub 同步源码。
3. 手工把新的 jar 上传到 ECS。
4. 停掉旧进程并启动新 jar。

这套方式不算自动化，但非常适合当前阶段：

1. 它稳定。
2. 它可控。
3. 它足够适配单台 ECS 演示环境。

## 为什么前端改动也要重新打包

因为当前前端资源位于：

1. src/main/resources/static/index.html
2. src/main/resources/static/app.js
3. src/main/resources/static/styles.css

这些资源最终会被打进 Spring Boot jar 包。因此：

1. 你哪怕只改了前端样式。
2. 或者只加了一个一键演示卡片。
3. 最终部署时也必须重新执行 Maven 打包。

## 本地发布步骤

### 1. 确认改动

```bash
git status --short
```

重点看：

1. 前端文件是否改对。
2. README 和 docs 是否包含预期内容。
3. 是否有不该带上的临时文件。

### 2. 构建校验

```bash
mvn -q -DskipTests package
```

这个命令至少要保证：

1. Java 代码仍可打包。
2. static 目录资源被正确收进 jar。

### 3. GitHub 同步

```bash
git add .
git commit -m "Add frontend ops dashboard and docs"
git push origin main
```

如果 docs 目录需要公开展示，这一步必须确保 `.gitignore` 不再排除 docs。

## ECS 发布步骤

### 1. 上传新 jar

```bash
scp target/aeroflow-sentinel-1.0-SNAPSHOT.jar root@118.31.221.81:/home/root/apps/superbizagent/SuperBizAgent-release-2026-01-02/target/
```

### 2. 停掉旧进程

服务器当前典型进程形态：

```bash
java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo
```

发布时要先确认旧 PID，再停止旧进程。

### 3. 启动新进程

```bash
nohup java -jar target/aeroflow-sentinel-1.0-SNAPSHOT.jar --spring.profiles.active=demo > app.log 2>&1 &
echo $! > app.pid
```

如果线上环境依赖 DASHSCOPE_API_KEY，需要确保启动时带上该环境变量。

### 4. 发布后验证

至少做下面四类校验：

1. 进程校验：确认新的 PID 存活。
2. 日志校验：确认启动类、端口和 profile 正确。
3. 页面校验：确认首页能打开，且前端驾驶舱可见。
4. 接口校验：确认 /api/chat 至少能返回一条有效回答。

## 这次前端驾驶舱发布时要特别关注什么

因为这次是可视化层增强，所以除了原来的接口验证，还需要额外检查：

1. 首页是否能看到一键演示场景卡片。
2. 服务端会话卡片是否会在提问后刷新。
3. 时间线是否能记录发送提问、模式切换和巡检完成。

## 为什么这份手册对面试也有价值

这份文档能证明你不仅写了功能，还掌握了：

1. 前端静态资源如何随 Spring Boot 一起发布。
2. GitHub 源码同步和服务器运行制品同步是两套动作。
3. 演示环境上线后应该怎样做发布验证。

## 一句话总结

当前 AeroFlow Sentinel 的发布方式虽然轻量，但已经具备“源码同步、制品替换、进程切换、结果验证”的完整上线闭环，足够支撑开源展示和面试演示。
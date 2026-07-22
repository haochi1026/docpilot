# 学习阶段前基线运行记录

运行时间：2026-07-22（Asia/Shanghai）

## 真实依赖验收

最终可复现脚本报告：`tests/results/acceptance_matrix_20260722-190214.json`

| 项目 | 结果 |
| --- | --- |
| Markdown、DOCX、原生 PDF、扫描 PDF | 4/4 SUCCESS |
| RocketMQ | `queueMode=rocketmq`，解析任务完成 |
| pgvector | 连接成功，向量维度 1024 |
| Ollama Embedding | `qwen3-embedding:0.6b`，连接测试通过 |
| OCR | 扫描 PDF 解析成功 |
| 上传并发 | 4 个任务全部接收，吞吐 16.35 req/s |
| 异步解析 | 4/4 SUCCESS，p95 1.60s |

该验收环境是一次性 Docker Compose 环境，执行结束后应删除卷；它证明了真实依赖路径可用，不代表多副本生产容量。

## 轻量并发基线

环境：已有 `docker-compose.system-e2e.yml`，本地队列、关闭向量/OCR，文本文件 8KB，上传和异步解析，不包含模型问答压力。

| 并发上传任务 | 接收吞吐 | 上传 p95 | 解析 p95 | 成功率 |
| ---: | ---: | ---: | ---: | ---: |
| 10 | 30.81 req/s | 113.10ms | 3.09s | 10/10 |
| 20 | 32.05 req/s | 27.16ms | 3.69s | 20/20 |
| 50（20 workers） | 76.59 req/s | 48.80ms | 5.69s | 50/50 |

Docker 资源快照显示，50 任务后 DocPilot Server 约 787.7MiB，MySQL 约 719.5MiB；这些是采样瞬时值，不是资源上限。

报告文件位于 `tests/results/light-load/`，每次运行还会保存 before/after Docker stats。

## 解读边界

- 上传吞吐受本地 Docker、数据库和并发脚本影响，不应直接转换为生产 QPS；
- 解析 p95 包含轮询间隔，不能等同于纯 CPU 处理耗时；
- 该轮没有压测 SSE、LLM Token 生成或 OCR 并发；
- 真实依赖验收和稳定系统 E2E 的配置不同，结果需要分别引用。

## AgentOps 重启恢复演练

演练运行：`0de8b2cd-a370-4396-bd56-044ff360651a`

1. 停止 AgentOps Worker；
2. 创建一条持久化评测运行；
3. 将运行模拟为 Worker 崩溃后遗留的 `RUNNING` 状态和过期租约；
4. 重启 AgentOps API，启动时恢复逻辑将运行重新置为可投递状态；
5. 启动 Worker，运行最终进入 `COMPLETED`，1/1 用例完成。

该演练验证 API 重启后的过期租约恢复、Outbox 重新投递和 Worker fencing 主链路。可通过 `scripts/run_agentops_restart_recovery.ps1` 重复执行。

# 学习阶段前的可复现基线

更新时间：2026-07-23（Asia/Shanghai）

这份基线用于把“已经验证的能力”和“生产环境仍需补充的能力”分开，避免把本地回归结果误写成生产 SLO。

## 当前已验证

| 范围 | 结果 | 运行方式 |
| --- | --- | --- |
| DocPilot + AgentOps 闭环 | PASS | `docker-compose.system-e2e.yml` + `tests/system_e2e.py` |
| AgentOps 回归集 | 160/160 | `agentops/scripts/seed_full_regression.py` |
| Java 单元测试 | PASS | `mvn -B -ntp verify` |
| Agent/AgentOps 单元测试 | PASS | 两个 Python 项目执行 `pytest` |
| 本次 Agent 上下文记忆回归 | PASS | DocPilot 35 tests，应用覆盖率约 76%；覆盖旧轮次压缩、摘要增量更新和工具调用轮次保留 |
| 本地词法/混合检索 | PASS | `scripts/retrieval_ablation.py` |

系统 E2E 是稳定回归环境，明确使用 `QUEUE_MODE=local`、`VECTOR_STORE_ENABLED=false`、`OCR_ENABLED=false`。因此它不代表 RocketMQ、pgvector 和 OCR 已通过真实依赖验收。

## 真实依赖最小验收

先确保本机 Ollama 已启动，并存在 `qwen3.5:2b` 和 `qwen3-embedding:0.6b`。在 PowerShell 中执行：

```powershell
Set-Location <docpilot-repository>
.\scripts\run_acceptance_matrix.ps1
```

脚本会启动一次性验收环境：MySQL、Redis、MinIO、pgvector、RocketMQ NameServer/Broker、Tesseract OCR，以及真实 Ollama Embedding 连接。

脚本使用四种 fixture（Markdown、DOCX 表格、原生 PDF、扫描 PDF），验证上传、异步解析、RocketMQ 投递、向量建索引和 OCR。报告保存到 `tests/results/acceptance_matrix_*.json`。

保留环境排查问题：

```powershell
.\scripts\run_acceptance_matrix.ps1 -KeepStack
docker compose -p docpilot-acceptance -f docker-compose.smoke.yml -f docker-compose.rocketmq.yml -f docker-compose.acceptance.yml logs server broker
docker compose -p docpilot-acceptance -f docker-compose.smoke.yml -f docker-compose.rocketmq.yml -f docker-compose.acceptance.yml down --volumes --remove-orphans
```

## 轻量并发基线

系统 E2E 启动后执行：

```powershell
Set-Location <docpilot-repository>
.\scripts\run_light_load.ps1 -BaseUrl http://127.0.0.1:28081
```

脚本分别运行 10、20、50 个并发上传/异步解析任务，记录上传延迟、解析完成延迟、成功率和 Docker 容器资源快照。报告保存在 `tests/results/light-load/`。

这是一项轻量基线，不是容量结论。报告中的 p95 只适用于当前机器、当前模型和当前 fixture，不能直接写成“系统支持 X QPS”。

AgentOps 任务恢复演练：

```powershell
.\scripts\run_agentops_restart_recovery.ps1
```

它会在系统 E2E 环境中模拟过期的 Worker 租约，重启 AgentOps API，再确认任务经 Outbox 重新投递并完成。

## 生产配置约束

DocPilot 的 `APP_ENV` 设置为 `production` 时，会拒绝默认或示例密钥，并要求 JWT、数据库、MinIO、向量库、Agent 身份和审批密钥满足最小长度且相互独立。模型和 Embedding HTTP 请求也有显式连接/读取超时：

- AI：连接 2 秒，读取 180 秒；
- Embedding：连接 2 秒，读取 60 秒。

开发环境可以使用 Compose 默认值，但生产部署必须通过外部环境变量或 Secret Manager 注入真实密钥。

## 尚未被这份基线证明的能力

- 多副本和跨节点故障转移；
- 持续压测下的容量和成本；
- 真实用户问题上的检索质量；
- 备份恢复演练和灾备；
- Kubernetes 滚动发布、镜像签名和漏洞扫描。

面试中应明确说明这些边界。

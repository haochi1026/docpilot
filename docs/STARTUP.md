# DocPilot 启动手册

## 一、默认模式启动（推荐首次使用）

默认使用本地线程池处理解析任务、使用本地检索答案生成器，不需要 RocketMQ、Ollama 或 API Key。

### 1. 启动 Docker Desktop

在 PowerShell 检查：

```powershell
docker version
docker compose version
```

### 2. 启动全部服务

```powershell
cd D:\internship\docpilot
Copy-Item .env.example .env
docker compose up -d --build
```

或执行：

```powershell
.\start.ps1
```

首次启动需要下载 MySQL、Redis、MinIO、Maven 和 Node 镜像。查看后端日志：

```powershell
docker compose logs -f server
```

出现 `Started DocPilotApplication` 后访问 <http://localhost:15174>。

### 3. 验证上传和问答

1. 使用部门主管账号 `manager / 123456` 登录。
2. 进入默认的“课题组资料库”。
3. 上传仓库自带的 `samples/课题组实验规范.md`，或其他 TXT、Word、带文本层的 PDF。
4. 等待右侧状态从 `PENDING` 变为 `SUCCESS`。
5. 在问答区提问，展开引用来源核对原文。

权限验证可使用以下账号：

- `student / 123456`：仅使用，不能创建知识库、上传文档或维护成员。
- `manager / 123456`：部门主管，可管理本部门知识库和成员授权。
- `admin / 123456`：平台管理员，可管理全部知识库。

MinIO 控制台为 <http://localhost:19001>，默认账号：

- 用户名：`minioadmin`
- 密码：`minioadmin123`

## 二、RocketMQ 模式

建议先确认默认模式可以正常运行，再切换：

```powershell
docker compose down
docker compose -f docker-compose.yml -f docker-compose.rocketmq.yml up -d --build
```

或执行：

```powershell
.\start-rocketmq.ps1
```

## 三、Ollama AI 增强模式

默认模式没有使用 Embedding 模型，也不会调用大模型。需要真实的向量检索和模型回答时执行：

```powershell
cd D:\internship\docpilot
.\start-ai.ps1
```

脚本会启动 Ollama，并下载：

- `nomic-embed-text`：文档片段和问题向量化。
- `qwen2.5:3b`：根据检索片段生成回答。

启动后登录工作台，在“空间概览”确认回答模式为“大模型归纳回答”、检索模式为“向量 + 词片混合检索”。已有文档需要点击“重建向量索引”。

检查 RocketMQ：

```powershell
docker compose -f docker-compose.yml -f docker-compose.rocketmq.yml logs -f namesrv broker server
```

## 四、接入 Windows 中已有的 Ollama（推荐）

如果本机已经安装并运行 Ollama，可以直接让 Docker 中的 DocPilot 调用 Windows 上的模型，不需要再启动一个 Ollama 容器。

当前机器已经安装：

- 问答模型：`qwen3.5:2b`
- Embedding 模型：`qwen3-embedding:0.6b`

执行：

```powershell
cd D:\internship\docpilot
.\start-local-ollama.ps1
```

脚本会检查两个模型是否存在，并使用 `host.docker.internal:11434` 将后端连接到 Windows Ollama。启动后登录管理员或部门主管账号，在已有知识库中点击“重建向量索引”。新上传的文档会自动生成向量。

如果以后更换模型，可以通过参数指定 Ollama 中显示的完整模型名称：

```powershell
.\start-local-ollama.ps1 -ChatModel "qwen3.5:2b" -EmbeddingModel "qwen3-embedding:0.6b"
```

可先用下面的命令确认模型名称：

```powershell
ollama list
```

如果脚本提示无法连接 Ollama，先确认 Ollama 桌面程序正在运行；仍无法访问时，在新的 PowerShell 窗口中执行：

```powershell
$env:OLLAMA_HOST="0.0.0.0:11434"
ollama serve
```

## 五、接入其他 OpenAI 兼容服务（可选）

先在 Windows 主机安装 Ollama，并拉取模型：

```powershell
ollama pull qwen3.5:2b
ollama serve
```

编辑 `.env`：

```env
AI_MODE=openai
AI_BASE_URL=http://host.docker.internal:11434/v1
AI_API_KEY=ollama
AI_MODEL=qwen3.5:2b
EMBEDDING_MODE=openai
EMBEDDING_BASE_URL=http://host.docker.internal:11434/v1
EMBEDDING_API_KEY=ollama
EMBEDDING_MODEL=qwen3-embedding:0.6b
```

重建后端：

```powershell
docker compose up -d --build server client
```

如果使用云端 OpenAI 兼容服务，将 `AI_BASE_URL`、`AI_API_KEY` 和 `AI_MODEL` 换成服务商提供的值。不要把真实密钥提交到 GitHub。

## 六、停止与清理

停止并保留数据：

```powershell
docker compose -f docker-compose.yml -f docker-compose.rocketmq.yml down
```

清空所有本项目数据：

```powershell
docker compose -f docker-compose.yml -f docker-compose.rocketmq.yml down -v
```

`-v` 会永久删除项目的 MySQL、Redis 和 MinIO 数据卷。

## 七、本地开发启动

需要 JDK 17+、Maven 3.9+、Node.js 18+，并先通过 Docker 启动 MySQL、Redis 和 MinIO：

```powershell
docker compose up -d mysql redis minio

cd server
mvn spring-boot:run

cd ..\client
npm install
npm run dev
```

开发前端地址为 <http://localhost:5174>，Vite 将 `/api` 代理到 8080。

## 八、常见问题

### 后端提示 MinIO 初始化失败

```powershell
docker compose ps
docker compose logs --tail 100 minio
```

确认 MinIO 健康后重启后端：`docker compose restart server`。

### 文档一直处于 PENDING

```powershell
docker compose logs --tail 200 server
```

默认模式应看到 Outbox 投递和解析日志。RocketMQ 模式还需检查 `namesrv`、`broker` 是否正常。

### PDF 解析后提示文本不足

该 PDF 很可能是扫描图片，没有文本层。先使用 OCR 软件生成可搜索 PDF，或上传 Word/TXT 验证流程。

### SSE 很久没有输出

确认 Nginx 已关闭该接口的代理缓冲，并查看后端是否触发限流。默认本地模式通常会立即返回。

### 端口冲突

DocPilot 使用 15174、18081、19001。修改 Compose 左侧端口即可，例如 `15184:80`。

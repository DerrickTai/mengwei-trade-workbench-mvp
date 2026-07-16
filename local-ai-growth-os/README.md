# Local AI Growth OS

一个面向本地商家的 GEO（Generative Engine Optimization）诊断产品：用可复现的 Prompt 测试，采集多模型回答，量化品牌在 AI 回答中的可见度，并给出有证据链的优化建议。

这不是内容群发或账号运营自动化工具。第一阶段明确不包含自动刷评论、自动注册账号、浏览器模拟登录、自动发帖，或任何违反平台规则的行为。

## 30 天可售版本的定义

商家可完成以下闭环：

1. 建立商家与品牌档案，维护可核验的品牌知识；
2. 选择问题集和模型，运行一次有时间、模型版本和输入快照的诊断；
3. 查看每个回答的品牌/竞品出现、位置与引用证据；
4. 获得可解释的 GEO Score、竞品对比和可编辑诊断报告；
5. 导出并向商家交付报告。

详细架构、评分和 30 天拆分见 [产品与架构设计 v0.1](docs/product-and-architecture-v0.1.md)。

面向销售演示与交付的边界说明见 [v0.1 可收费演示交付说明](docs/delivery-v0.1.md)。

## 技术边界

- 模块化单体：Java 21、Spring Boot 3、PostgreSQL、Redis、Flyway、Vue 3、TypeScript。
- 模型调用通过供应商官方 API 或企业已授权的 OpenAI 兼容接口完成；模型密钥只来自环境变量或加密配置。
- 诊断结果是“所选模型、版本、Prompt 与时间范围内的观测”，不是搜索排名承诺。
- 所有分数、提及判断和报告均能回溯到具体测试运行及版本化计算规则。

## 当前可演示闭环（v0.1）

此版本已实现以下完整演示链路：

1. 登录并进入租户隔离的商家工作台；
2. 创建商家，录入品牌名称、别名、服务、已验证主张与证据链接；
3. 配置待测试的本地生活问题和竞争品牌；
4. 以确定性的 `MockProvider` 运行测试并保存回答快照；
5. 用 `GEO_SCORE_V1` 计算出现覆盖、位置显著性、信息一致性和竞争可见度；
6. 生成带样本边界与行动建议的 Markdown 报告。

Mock 模型只用于产品演示和自动化测试，页面与报告会明确标识。不得将它作为真实 AI 平台的观察或对外效果承诺。

## 本地启动

### Docker（推荐）

```bash
cd /Users/derrick/Documents/营销/local-ai-growth-os
export APP_AUTH_TOKEN='请替换为足够长的随机值'
docker compose up --build
```

### 真实 OpenAI 兼容内容生成

后端只从环境变量读取真实模型配置，API Key 不会返回前端，也不会写入调用记录：

```bash
export OPENAI_COMPATIBLE_BASE_URL='https://your-compatible-endpoint/v1'
export OPENAI_COMPATIBLE_API_KEY='your-secret-key'
export OPENAI_COMPATIBLE_MODEL='your-model-name'
docker compose up --build -d
```

未配置时，Provider 状态会显示为不可用；资料不足会在调用模型前阻断，并保存 `BLOCKED` 调用记录。

- 工作台：http://localhost:5174
- 后端 OpenAPI：http://localhost:8081/swagger-ui/index.html
- MinIO Console：http://localhost:9001
- 演示登录：`demo@localgrowth.test` / `Demo123!`（仅开发环境；首次启动自动创建）

### 前后端分别启动

先启动依赖：`docker compose up postgres redis minio`。

```bash
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
```

## 交付边界与下一步

- 真实模型接入必须使用客户已授权的官方/OpenAI 兼容 API，并配置模型版本、预算、限流和密钥加密；本版只开放了 Provider 抽象与 MockProvider。
- 报告当前为在线 Markdown 预览。下一轮增加 PDF/JSON 归档、真实模型连接配置、提及人工复核和评分趋势比较。
- 不包含自动发帖、自动注册、刷评、模拟登录或任何平台违规自动化。

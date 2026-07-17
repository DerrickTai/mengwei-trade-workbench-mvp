# M5：品牌诊断、竞品与信源策略中心 MVP

**状态：已实现（2026-07-17）**  
**规则版本：`GEO_STRATEGY_V1`**

## 1. 定位与边界

M5 将 M3/M4 的单条人工 AI 观察转化为可交付的、不可变的品牌诊断快照和整改策略。M3 是“问题与观察”工作台：录入问题、原始回答、截图、引用和人工核验；M5 不采集回答，也不重新解释模型内部推理，只聚合当前时间范围内满足口径的观察。

正式诊断仅使用 `ai_observations.verification_status = VERIFIED`、`demo = false`、未删除的记录。演示数据、Mock 结果与未核验记录不会进入正式分母或客户诊断。分母为零时，比例指标返回 `null`，页面显示“—”，并产生“补齐已核验人工观察样本”策略，而不是虚构高分。

M5 不展示或宣称获得模型内部思维链；所有结论只描述观察到的时间关联，不主张内容或整改动作与 AI 表现变化存在确定因果关系。

## 2. 事实与识别配置

M1 的 `merchant_facts` / `merchant_fact_versions` 是品牌正式事实源；M5 不复制或改写这些事实。`geo_strategy_configs` 保存的是诊断识别口径：

| 字段 | 用途 |
| --- | --- |
| `brand_terms`、`ambiguous_terms` | 品牌识别词与歧义提示 |
| `competitor_ids` | 当前商家的竞品范围 |
| `question_ids`、`target_platforms` | 纳入诊断的消费者问题和 AI 平台 |
| `owned_domains` | 自有来源域名，用于信源归类 |
| `notes`、审计字段、`version` | 配置说明、变更追溯和乐观锁 |

配置按 `(tenant_id, merchant_id)` 唯一；所有读取和写入均同时校验租户与商家。默认配置从当前 VERIFIED 的 `BRAND_NAME` 以及启用问题集取得，但保存后的配置独立版本化。

## 3. 不可变诊断快照

每次 `POST /geo-diagnoses` 创建 `geo_diagnosis_snapshots`，保存规则版本、样本时间、观察数量、配置快照，以及指标、平台、问题、竞品、信源、Gap、策略 JSON 快照。快照只插入不更新，因此后续观察、事实或配置变化不会覆盖历史报告口径。

`optimization_tasks.diagnostic_run_id` 在既有模型中不可为空。为兼容该约束，M5 每次创建一个仅供本快照关联的 `diagnostic_runs` 记录，provider 为 `M5_MANUAL_OBSERVATION`。这不是旧 Mock 诊断运行，也不会把 Mock 数据作为正式诊断输入。

## 4. 聚合指标与信源

M5 计算整体、按平台、按问题的：有效观察数、品牌提及数/率、推荐数/率、TOP3 数/率、已核验事实数、事实错误数和事实准确率；竞品从 `mentioned_competitors` 聚合出现次数。

`cited_sources` 以容错方式解析：无效 JSON 视为空数组，不会中断诊断。来源分类规则位于服务层：自有域名为 `OWNED`，大众点评/美团为 `LOCAL_PLATFORM`，小红书为 `SOCIAL_PLATFORM`，可解析的其他 URL 为 `EXTERNAL_WEB`，无法解析的 URL 为 `UNKNOWN`。

M5 同时汇总：M1 当前未 VERIFIED 事实数、M2 `platform_asset_gaps` 的 OPEN Gap、M3/M4 未解决的 `ai_observation_fact_issues`。平台资料或观察不会反向覆盖正式事实中心。

## 5. 策略与任务

策略规则是确定性、可解释的：样本不足、提及率偏低、推荐率偏低、存在事实错误、存在平台资产 Gap，分别映射为说明、优先级和既有 `optimization_tasks` 类型。`POST /geo-diagnoses/{snapshotId}/optimization-tasks` 使用 `geo_strategy_task_links` 的 `(tenant_id, snapshot_id, strategy_code)` 唯一约束实现幂等；不依赖任务 description 的文本匹配。

## 6. API 与页面

| API | 实现能力 |
| --- | --- |
| `GET/PUT /geo-strategy-config` | 查看/保存监测配置 |
| `GET /geo-strategy-dashboard` | 当前正式口径的实时聚合预览 |
| `POST /geo-diagnoses` | 生成不可变 M5 快照 |
| `GET /geo-diagnoses`、`GET /geo-diagnoses/{id}` | 快照列表与详情 |
| `POST /geo-diagnoses/{id}/optimization-tasks` | 从策略幂等生成整改任务 |

“诊断与策略”页面展示配置、整体卡片、平台/问题表现、竞品与信源、聚合 Gap、策略和历史快照，并固定展示方法限制与因果免责声明。

## 7. 验收与已知限制

`scripts/m5-strategy-smoke-test.sh` 使用调用方提供的账号、密码和商家 ID：临时创建一条 `VERIFIED + demo=false` 观察，生成快照，连续两次创建任务验证幂等，再清理临时观察、快照、运行和任务。脚本不保存应用密钥或凭据。

当前限制：信源分类为规则分类，不判断网页权威性；竞品依赖人工录入观察；没有自动抓取、登录、发布、Cookie 池或评论操作；复杂语义歧义仍需人工核验。作品/内容资产的引用追踪、跨作品影响分析和更细的效果归因属于 M6/M7 后续范围，且仍只表达时间关联。

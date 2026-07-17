# M1-B1 已知限制

当前 M1-B1 按 MVP 范围完成运行闭环。

- `ProhibitedClaimGuard` 已实现，并接入真实内容生成流程。
- 服务端高风险内容审核阻断已实现。
- `ProhibitedClaimGuardTest` 已保留。
- HIGH/LOW 禁止宣传场景的完整集成测试尚未执行。
- `RealContentService` 和 `ExecutionService` 集成测试尚未补齐。
- 后续需要补充自动化测试、测试数据库和可控 Provider 替身。
- 当前规则是确定性关键词匹配，不得对外宣称可以识别所有语义变体、同义改写或隐含表达。

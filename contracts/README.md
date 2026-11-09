# 共享契约与对照样例

本目录为 iOS、Android 与共享服务提供可复用的格式及验证依据。当前阶段只归位已存在的样例，不新增协议版本或虚构完整 schema。

- [既有对照样例](fixtures/android-parity/README.md)：从根 `test-fixtures/` 原样迁入 `contracts/fixtures/`；目录中的 android-parity 名称保留其来源语境。
- 样例 JSON、`.bmbackup`、`.bmteam` 和 manifest 的原始字节保持；manifest 的摘要与历史来源继续有效。
- 样例均为既有合成资料；不读取真实用户比赛、生产凭据或线上数据。
- Swift／Kotlin 核心各自维护。`payloadVersion`、服务 `formatVersion` 与协议版本分别解释，不把测试驱动步骤当作线上协议。
- 原有工具入口仍在 `scripts/android/`，本轮只更新样例读取位置；Android Gradle 的单元测试及仪器测试资源引用同步调整。

在仓库根目录运行 `python3 scripts/validation/verify-docs-contracts.py` 可验证目录边界、冻结样例及现行导航。完整跨端互导是否完成仍以各平台版本计划为准。

以后补充正式 schema 时须从实际实现提取并验证两端兼容；Swift 与 Kotlin 的实现不会自动成为共用原生核心。更改参考样例需单独说明业务差异和来源，不在目录整理中重新生成样例。

# AN05 服务恢复后人工核对（2026-10-28，第九组）

基线 `ff234a9`。迁移 iOS `ServiceRecoveryReviewView`、`SharedMatchCoordinator.reconcileAfterRecovery` 和 `TeamSharingStore.reconcileAfterRecovery` 的明确确认流程；不修改服务／iOS，不自动续发恢复前的操作。

## 行为及持久性

恢复代次变化时，先把原身份操作、每场交接请求和本人负责／有待处理操作的比赛完整文件保留到独立加密目录的 recoveryWork，再清掉活动缓存。保留记录从共享列表的“查看本机保留记录”进入，可只读查看原逐球、操作和纠错审计；不授予当前球队访问，也不提供自动重发或上传。

用户明确选择“保留本机记录并核对”后，读取当前球队／成员、名册、全部分页比赛及完整 payload；保留未知字段和删除事实，核对前后球队／权限及 recoveryEpoch 一致，再持久保存旧工作，POST 现行 `/devices/recovery-review`，并在确认后再次核对权限。全部完成并成功写入本机后，才清除本机恢复阻断、将旧请求移出活动队列。旧内容仍在只读保留区。

确认响应丢失或本机最终保存失败时，恢复阻断与旧请求仍保留；再次核对使用当前同一代次的幂等确认，不重做旧业务操作。核对期间再次恢复或权限变化，整批不应用。新旧代次不会因为一次按钮点击就混为可写状态。若恢复恰好发生在权限读取与业务 POST 之间，原服务返回 recovery_review／recovery_changed 时立即关闭本机写入口。

本组同步按 lint 建议拆开 Manifest 的 scheme／host 属性，路由含义不变；邀请待办不会误填到配对表单。上一链接阶段实际 lint 18 警告，已更正历史文档的误写计数。

## 开发验证

新增原服务回归：恢复前待建球队／交接及原始比赛保留、明确确认要求、完整当前文件读取、确认丢失、本机最终提交失败、再次恢复／授权变化、恢复发生在业务 POST 前、核对后不重发旧操作。独立 Android 加密文件重开验证保留的原请求正文，秘密不以明文落盘或出现在对象描述。

执行 `:core:test :core:teamServiceTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 最终 BUILD SUCCESSFUL，扩展权限变化子场景后另跑 `:core:teamServiceTest` 通过。**140 core＋48 原服务＋51 app＝239 项，0 失败／错误／跳过**；lint 0 错误、15 警告。新增的三条 Manifest 属性写法警告已消除，历史链接组仍按当时 18 条登记。复用的任务不冒充全部重跑。

日志 `output/validation/android/an05-recovery-review/final.log`、`permissions-followup.log`，冻结 XML／lint／源码哈希在 `freeze/`，生成文件不推送。**AN07 按用户要求不做**；没有 Activity／系统／浏览器／真实双端测试，没有生产调用、部署、购买、签名发布、APK 分发或 main 操作。

## 接续

恢复核对解除的是已完成核对的服务代次阻断，不替代撤销 Bearer 后的明确重接入，也不提供尚未实现的共享记分编辑／PUT／离线队列。AN04 完整转换、AN05 其余功能和 AN06 继续按统一交接；不要把本组通过写成完整迁移或 AN07 通过。

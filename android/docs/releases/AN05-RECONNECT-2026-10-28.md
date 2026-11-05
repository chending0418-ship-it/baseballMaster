# AN05 第十一组：失效授权重新接入（2026-10-28）

对应 iOS TeamSharingStore.reconnectExpiredDevice／TeamServiceClient.reconnectWithNewCredential，只实现 Android 适配，不改服务或 iOS。不是共享比赛完整迁移完成；AN07 本轮跳过。

## 行为与持久边界

- 任意 TeamDirectoryClient 认证请求遇到确定的 401，立即隐藏旧球队角色和秘密，先把旧目录请求、交接请求及本人完整比赛缓存保存在加密 recoveryWork。原请求仍可核对，不会自动换身份重发。保存失败也通过进程内标记隐藏旧授权；原磁盘文件保留。
- “球队共享”显示授权失效说明和“重新接入”。必须明确确认；新身份之后仍需邀请、原设备配对或管理恢复，不能恢复原角色。
- CredentialVault 先固定独立的 state_sealed 文件密钥，再持久保存随机新候选凭证及续接编号。原认证不因生成候选而替换。普通业务请求在候选存在时阻断，只允许明确续接。
- TeamDeviceReconnector 先持久隔离旧工作，再用候选向现有 POST devices 注册。丢注册响应、凭证激活失败、最终目录保存失败都重用同一候选，原服务按 token 返回同一新 deviceID。确认新凭证持久化后才保存新目录；两者完成后才清候选。清理失败仍可续接，不能因待办外观已消失就发送业务请求。
- 新身份保持 requiresRecoveryReview，明确核对后才开放操作。旧邀请、卡片、配对、绑定和授权缓存不会以新身份恢复；本机 Room 球队、比赛及来源原件完全独立。新凭证不改变既有加密文件密钥。候选、认证和保留请求均不进入普通备份、报告、日志或字符串描述。

## 开发验证

`output/validation/android/an05-reconnect/initial.log`：core、隔离真实服务、Android JVM、App／AndroidTest 编译和 lint 全部 BUILD SUCCESSFUL。**259 项（145 core＋56 原服务＋58 app），0 失败／错误／跳过，lint 0 错误／18 警告**。后续补强旧角色隐藏的测试后单独重新执行 teamServiceTest，见 followup.log。冻结 XML 和摘要在 freeze。

新增回归包括真实服务撤销旧凭证后的明确重新接入、旧请求及本人比赛保留、不自动发送旧创建／交接、丢注册响应、候选／归档保存失败时零注册请求、激活／最终目录／清理失败后同候选恢复，以及 401 状态写失败仍隐藏旧角色。Android JVM 使用真实 SharedPreferences、AtomicFile 和 AES，验证重新打开候选、认证切换后稳定密钥、未确认 commit 阻断所有同进程 vault。真实服务仅 127.0.0.1 动态端口、内存 SQLite，不用生产。

没有运行 Activity、真实 Keystore／锁屏、系统分享／剪贴板、真实进程杀死、磁盘填满或 AN07。未声称真实 iOS／Android 双端联调。

## 接手位置

core TeamDeviceReconnect、TeamDirectoryClient／Session；App CredentialVault、TeamDirectoryFile、AndroidTeamSharing、TeamSharingScreen。测试 TeamDirectoryIntegrationTest 和 CredentialRotationStorageTest。继续 AN05 完整共享建赛／绑定、正式 payload 和离线更新，及 AN06 直播／历史权限；不要把本组重新接入当作实现了这些功能。

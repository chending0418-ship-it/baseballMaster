# 中英文隐私补充稿

供官网合入现有政策，保留现有 Android 流程。定稿时补入已核实的服务商处理及保留信息。

**球队赛程。** 管理员或记分员选择发布时，对阵双方、开赛时间、场地、球队与来源标识会保存到球队服务，供该队当前成员查看。改期和取消会更新同一条记录。仅保存比赛安排时，该安排保存在设备上。

**比赛分析。** 点「生成比赛分析」后，所选比赛范围的统计、关键局面与球员代号经球队服务发送给第三方 AI 服务 V-API，生成综述与球员评价。球员姓名保留在本机并在展示报告时回填。用户自行决定是否分享报告。

**iOS 语音记分。** 使用开启码开通时，开启码和设备标识发送到语音服务。每次开始录音前，App 说明接收方与数据用途，并在用户同意后请求麦克风权限和临时识别会话。取得会话时会提交必要比赛局面，音频直接传给腾讯云语音识别。用户可检查和编辑转写；点「解析局面」后，转写及必要局面经语音服务发送给腾讯云 TokenHub 混元，生成记分建议。局面可能含球员姓名、背号、标识、比分与垒况。用户确认后才写入比赛，也可继续手动记分。

**Team schedules.** When an administrator or scorer publishes a schedule, the team service stores the opposing teams, start time, venue, team identifier and source identifier for current team members. Rescheduling and cancellation update the same entry. A locally saved arrangement stays on the device.

**Game analysis.** When you select Generate Game Analysis, statistics, key situations and player aliases for the selected games are sent via the team service to V-API, a third-party AI service, to generate a summary and player evaluations. Player names remain on the device and are restored for display. You choose whether to share the report.

**iOS voice scoring.** Redeeming an activation code sends the code and a device identifier to the voice service. Before each recording, the app explains the recipients and purposes. With your consent, it requests microphone access and a temporary recognition session using the necessary game situation. Audio streams directly to Tencent Cloud Speech Recognition. You can check or edit the transcript. Tapping Analyze Play sends the transcript and necessary game situation via our voice service to Tencent Cloud TokenHub/Hunyuan for a scoring suggestion. The situation may include player names, jersey numbers, identifiers, scores and base occupancy. You decide whether to record the proposed play and can use manual scoring.

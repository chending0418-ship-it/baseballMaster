package cc.baseballmaster.scorekeeper

import cc.baseballmaster.core.AnalysisInput
import cc.baseballmaster.core.AnalysisInvalidReport
import cc.baseballmaster.core.AnalysisReport
import cc.baseballmaster.core.AnalysisState
import cc.baseballmaster.core.TeamDirectorySession
import cc.baseballmaster.core.analysisDevice
import cc.baseballmaster.core.analysisRequest
import cc.baseballmaster.core.analysisStatus
import cc.baseballmaster.core.startAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

internal class AnalysisProviderFailure : Exception("这次分析未生成，请稍后重试。")
internal class AnalysisTimeout : Exception("生成时间较长，请稍后重试。")

internal suspend fun TeamDirectorySession.generateAnalysis(
    input: AnalysisInput,
    requestID: String,
    pollMillis: Long = 2_000,
    timeoutMillis: Long = 120_000,
): AnalysisReport {
    val request = analysisRequest(input, requestID)
    return withTimeoutOrNull(timeoutMillis) {
        val deviceID = runInterruptible(Dispatchers.IO) { analysisDevice() }
        var status = runInterruptible(Dispatchers.IO) { startAnalysis(deviceID, request) }
        while (status.state == AnalysisState.pending) {
            delay(pollMillis)
            status = runInterruptible(Dispatchers.IO) { analysisStatus(deviceID, request.id) }
        }
        if (status.state == AnalysisState.failed) throw AnalysisProviderFailure()
        (status.report ?: throw AnalysisInvalidReport()).verify(input)
    } ?: throw AnalysisTimeout()
}

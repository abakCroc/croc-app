package com.dking.crocapp.croc

import android.content.Context
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class CrocProcess(
    private val context: Context,
    private val prefsRepository: UserPreferencesRepository
) {
    companion object {
        private const val TAG = "CrocProcess"

        fun buildConfigJson(
            args: List<String>,
            env: Map<String, String>,
            workDir: String
        ): String {
            val json = JSONObject()
            json.put("args", JSONArray(args))
            val envObj = JSONObject()
            env.forEach { (k, v) -> envObj.put(k, v) }
            json.put("env", envObj)
            json.put("workDir", workDir)
            return json.toString()
        }
    }

    private val _state = MutableStateFlow<CrocTransferState>(CrocTransferState.Idle)
    val state: StateFlow<CrocTransferState> = _state.asStateFlow()

    @Volatile
    private var pipePfd: ParcelFileDescriptor? = null

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        val wm = wifiManager ?: return
        releaseMulticastLock()
        val lock = wm.createMulticastLock("croc").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {
            }
        }
        multicastLock = null
    }

    private data class ProcessResult(
        val exitCode: Int,
        val fileNames: List<String>,
        val totalBytes: Long,
        val outputTail: List<String>,
        val peerIp: String = "",
        val totalFileCount: Int = 0,
        val receivedText: String? = null
    )

    private val homeDir: File
        get() = File(context.filesDir, "croc-home").also { it.mkdirs() }

    private val tmpDir: File
        get() = File(context.cacheDir, "croc-tmp").also { it.mkdirs() }

    private fun secretEnv(code: String?): Map<String, String> {
        return if (code.isNullOrBlank()) emptyMap() else mapOf("CROC_SECRET" to code)
    }

    private fun buildGlobalFlags(prefs: UserPreferencesRepository.CrocPreferences): List<String> {
        val relayAddress = resolveRelayAddress(prefs.relayAddress)

        return buildList {
            if (prefs.useInternalDns) add("--internal-dns")

            if (relayAddress.isNotBlank()) {
                add("--relay"); add(relayAddress)
            }
            if (prefs.relayPassword.isNotBlank()) {
                add("--pass"); add(prefs.relayPassword)
            }
            if (prefs.pakeCurve.isNotBlank()) {
                add("--curve"); add(prefs.pakeCurve)
            }
            if (prefs.forceLocal) add("--local")
            if (prefs.disableCompression) add("--no-compress")
            if (prefs.uploadThrottle.isNotBlank()) {
                add("--throttleUpload"); add(prefs.uploadThrottle)
            }
            if (prefs.multicastAddress.isNotBlank() && prefs.multicastAddress != "239.255.255.250") {
                add("--multicast"); add(prefs.multicastAddress)
            }
        }
    }

    private fun resolveRelayAddress(relayAddress: String): String {
        if (relayAddress.isBlank()) return relayAddress

        val parsed = parseRelayHostPort(relayAddress) ?: return relayAddress
        val (host, port) = parsed
        if (isIpLiteral(host)) return relayAddress

        return try {
            val resolved = InetAddress.getAllByName(host)
                .sortedBy { if (it is Inet4Address) 0 else 1 }
                .firstOrNull()
                ?: return relayAddress

            val ip = when (resolved) {
                is Inet6Address -> "[${resolved.hostAddress}]"
                else -> resolved.hostAddress
            }
            val resolvedAddress = "$ip:$port"
            Log.i(TAG, "Resolved relay '$relayAddress' to '$resolvedAddress'")
            resolvedAddress
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve relay '$relayAddress', using original", e)
            relayAddress
        }
    }

    private fun parseRelayHostPort(relayAddress: String): Pair<String, Int>? {
        return try {
            val uri = URI("relay://$relayAddress")
            if (uri.host.isNullOrBlank() || uri.port == -1) null else uri.host to uri.port
        } catch (_: Exception) {
            null
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || ":" in host
    }

    suspend fun send(filePaths: List<String>, code: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()

                val command = mutableListOf("croc", "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("--ignore-stdin")
                    add("send")
                    add("--no-local")
                    add("--no-multi")
                    addAll(filePaths)
                }
                val workDir = File(filePaths.first()).parentFile ?: homeDir

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = workDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = "Send"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun sendText(text: String, code: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()

                val command = mutableListOf("croc", "--yes").apply {
                    addAll(buildGlobalFlags(prefs))
                    add("--ignore-stdin")
                    add("send")
                    add("--no-local")
                    add("--no-multi")
                    add("--text"); add(text)
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = homeDir,
                    waitingState = CrocTransferState.WaitingForPeer(code ?: "generating..."),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = "SendText"
                )
            } catch (e: Exception) {
                Log.e(TAG, "SendText failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    suspend fun receive(code: String, outputDir: File) {
        withContext(Dispatchers.IO) {
            try {
                _state.value = CrocTransferState.Preparing
                val prefs = prefsRepository.preferencesFlow.first()
                outputDir.mkdirs()

                val command = mutableListOf("croc", "--yes", "--overwrite").apply {
                    addAll(buildGlobalFlags(prefs))
                }

                executeWithDnsFallback(
                    baseCommand = command,
                    workDir = outputDir,
                    waitingState = CrocTransferState.WaitingForPeer(code),
                    extraEnv = secretEnv(code),
                    prefs = prefs,
                    opName = "Receive",
                    holdMulticastLock = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Receive failed", e)
                _state.value = CrocTransferState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun cancel() {
        try {
            croc.Croc.cancel()
        } catch (_: Exception) {}
        closePipeFd()
        releaseMulticastLock()
        _state.value = CrocTransferState.Cancelled
    }

    fun reset() {
        cancel()
        _state.value = CrocTransferState.Idle
    }

    private fun closePipeFd() {
        val old = pipePfd
        pipePfd = null
        try {
            old?.close()
        } catch (_: Exception) {}
    }

    private suspend fun executeWithDnsFallback(
        baseCommand: MutableList<String>,
        workDir: File,
        waitingState: CrocTransferState,
        extraEnv: Map<String, String>,
        prefs: UserPreferencesRepository.CrocPreferences,
        opName: String,
        holdMulticastLock: Boolean = false
    ) {
        Log.d(TAG, "$opName command: ${redactCommandForLog(baseCommand)}")

        if (holdMulticastLock) acquireMulticastLock()
        try {
            var result = runCommand(baseCommand, workDir, waitingState, extraEnv)

            if (shouldRetryWithInternalDns(result, prefs, baseCommand)) {
                val retryCommand = baseCommand.toMutableList()
                addInternalDnsFlag(retryCommand)
                Log.w(TAG, "$opName retry with --internal-dns: ${redactCommandForLog(retryCommand)}")
                closePipeFd()
                result = runCommand(retryCommand, workDir, waitingState, extraEnv)
            }

            val exitCode = try {
                croc.Croc.waitDone().toInt()
            } catch (_: Exception) {
                -1
            }
            result = result.copy(exitCode = exitCode)
            closePipeFd()

            if (_state.value is CrocTransferState.Cancelled) {
                return
            }

            if (isSuccessfulTransfer(result)) {
                _state.value = CrocTransferState.Completed(
                    fileNames = result.fileNames,
                    totalBytes = result.totalBytes,
                    peerIp = result.peerIp,
                    totalFileCount = result.totalFileCount.coerceAtLeast(result.fileNames.size),
                    receivedText = result.receivedText
                )
            } else {
                _state.value = CrocTransferState.Error(errorMessageFor(result))
            }
        } finally {
            if (holdMulticastLock) releaseMulticastLock()
        }
    }

    private suspend fun runCommand(
        command: List<String>,
        workDir: File,
        waitingState: CrocTransferState,
        extraEnv: Map<String, String>
    ): ProcessResult {
        val env = buildMap {
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            putAll(extraEnv)
        }

        val configJson = buildConfigJson(
            args = command,
            env = env,
            workDir = workDir.absolutePath
        )

        // Close any previous PFD before creating a new one.
        closePipeFd()

        val fd = croc.Croc.start(configJson).toInt()
        if (fd < 0) {
            throw IllegalStateException("crocStart returned fd=$fd")
        }

        val pfd = ParcelFileDescriptor.adoptFd(fd)
        pipePfd = pfd

        _state.value = waitingState

        val inputStream = FileInputStream(pfd.fileDescriptor)
        return parseOutput(inputStream)
    }

    private fun redactCommandForLog(command: List<String>): String {
        val redacted = command.toMutableList()
        var i = 0
        while (i < redacted.size) {
            if ((redacted[i] == "--pass" || redacted[i] == "--code") && i + 1 < redacted.size) {
                redacted[i + 1] = "****"
                i++
            }
            i++
        }
        return redacted.joinToString(" ")
    }

    private fun shouldRetryWithInternalDns(
        result: ProcessResult,
        prefs: UserPreferencesRepository.CrocPreferences,
        command: List<String>
    ): Boolean {
        if (result.exitCode == 0) return false
        if (prefs.useInternalDns) return false
        if (command.contains("--internal-dns")) return false

        return result.outputTail.any {
            val line = it.lowercase()
            ("lookup" in line && "[::1]:53" in line) ||
                    "no such host" in line ||
                    "server misbehaving" in line
        }
    }

    private fun addInternalDnsFlag(command: MutableList<String>) {
        if (command.contains("--internal-dns")) return
        val index = if (command.size > 1) 2 else 1
        command.add(index, "--internal-dns")
    }

    private fun errorMessageFor(result: ProcessResult): String {
        if (hasCliUsageExit(result)) {
            return "Transfer failed: croc rejected the command syntax and printed usage help."
        }
        if (result.outputTail.any { "no files transferred" in it.lowercase() }) {
            return "Transfer failed: no files were transferred."
        }
        if (result.exitCode == 0) {
            return "Transfer failed: croc exited without starting a file transfer."
        }

        val usefulLine = result.outputTail
            .asReversed()
            .firstOrNull { it.isNotBlank() }
            ?.trim()

        return if (usefulLine.isNullOrBlank()) {
            "Transfer failed (exit code ${result.exitCode})"
        } else {
            "Transfer failed: $usefulLine"
        }
    }

    private fun hasCliUsageExit(result: ProcessResult): Boolean {
        return result.outputTail.any {
            val line = it.lowercase()
            "on unix systems, to receive with croc you either need" in line ||
                    "on unix systems, to send with a custom code phrase" in line
        }
    }

    private fun isSuccessfulTransfer(result: ProcessResult): Boolean {
        if (result.exitCode != 0 || hasCliUsageExit(result)) return false
        if (result.fileNames.isNotEmpty() || result.totalBytes > 0L) return true
        if (result.peerIp.isNotBlank()) return true

        return result.outputTail.any {
            val line = it.lowercase()
            "sending '" in line || "receiving '" in line ||
                    "sending (" in line || "receiving (" in line
        }
    }

    private suspend fun parseOutput(inputStream: InputStream): ProcessResult {
        val reader = BufferedReader(InputStreamReader(inputStream))
        val fileNames = mutableListOf<String>()
        var totalBytes = 0L
        var currentFileName = ""
        var peerIp = ""
        var totalFilesFromProgress = 0
        val outputTail = ArrayDeque<String>()
        var isTextTransfer = false
        var capturingText = false
        val receivedTextLines = mutableListOf<String>()

        val peerIpRegex = Regex("""(?:->|<-)(\d+\.\d+\.\d+\.\d+)""")
        val progressLineRegex = Regex("""^\s*(.+?)\s+(\d+)%\s*\|.*\|\s*\((.+?)\)\s*(?:(\d+)/(\d+))?""")
        val sizeInProgressRegex = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*(\w+)""")
        val oldSendingRegex = Regex("""'([^']+)'""")
        val oldSizeRegex = Regex("""\((\d+(?:\.\d+)?)\s*(\w+)\)""")

        val fileSizeMap = mutableMapOf<String, Long>()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null && coroutineContext.isActive) {
                val l = line ?: continue
                Log.d(TAG, "croc> $l")
                outputTail.addLast(l)
                if (outputTail.size > 50) outputTail.removeFirst()

                if (l.isBlank()) continue

                if (l.contains("Code is:")) {
                    val code = l.substringAfter("Code is:").trim()
                    _state.value = CrocTransferState.WaitingForPeer(code)
                    continue
                }

                if (l.contains("Receiving text message")) {
                    isTextTransfer = true
                    oldSizeRegex.find(l)?.let { match ->
                        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[2]
                        totalBytes = parseSize(num, unit)
                    }
                    continue
                }

                if (capturingText) {
                    receivedTextLines.add(l)
                    continue
                }

                if (l.contains("Sending") || l.contains("Receiving")) {
                    peerIpRegex.find(l)?.let { match ->
                        peerIp = match.groupValues[1]
                    }
                    if (isTextTransfer && l.contains("Receiving")) {
                        capturingText = true
                        continue
                    }
                    oldSendingRegex.find(l)?.let { match ->
                        currentFileName = match.groupValues[1]
                        if (currentFileName !in fileNames) fileNames.add(currentFileName)
                    }
                    oldSizeRegex.find(l)?.let { match ->
                        val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[2]
                        totalBytes = parseSize(num, unit)
                    }
                    continue
                }

                val progressMatch = progressLineRegex.find(l)
                if (progressMatch != null) {
                    val match = progressMatch
                    val truncatedName = match.groupValues[1].trim()
                    val percent = match.groupValues[2].toIntOrNull() ?: 0
                    val sizeSection = match.groupValues[3]
                    val currentFileNum = match.groupValues[4].toIntOrNull()
                    val totalFileNum = match.groupValues[5].toIntOrNull()

                    if (truncatedName.isNotBlank()) {
                        currentFileName = truncatedName
                    }

                    sizeInProgressRegex.find(sizeSection)?.let { sizeMatch ->
                        val fileTotal = sizeMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                        val unit = sizeMatch.groupValues[3]
                        val fileTotalBytes = parseSize(fileTotal, unit)
                        fileSizeMap[currentFileName] = fileTotalBytes
                    }

                    if (totalFileNum != null && totalFileNum > 0) {
                        totalFilesFromProgress = totalFileNum
                    }

                    if (percent == 100 && currentFileName.isNotBlank()) {
                        if (currentFileName !in fileNames) {
                            fileNames.add(currentFileName)
                        }
                    }

                    val cumulativeTotal = fileSizeMap.values.sum()
                    if (cumulativeTotal > 0) {
                        totalBytes = cumulativeTotal
                    }

                    val completedBytes = fileNames.filter { it != currentFileName }
                        .sumOf { fileSizeMap[it] ?: 0L }
                    val currentFileSize = fileSizeMap[currentFileName] ?: 0L
                    val currentFileTransferred = (currentFileSize * percent / 100)
                    val bytesTransferred = completedBytes + currentFileTransferred

                    val effectiveTotalFiles = totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1)
                    val effectiveCurrentFile = if (currentFileNum != null) currentFileNum else fileNames.indexOf(currentFileName) + 1

                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = effectiveCurrentFile.coerceAtLeast(1),
                        totalFiles = effectiveTotalFiles,
                        currentFilePercent = percent,
                        bytesTransferred = bytesTransferred.coerceAtMost(totalBytes.coerceAtLeast(1)),
                        totalBytes = totalBytes.coerceAtLeast(1),
                        peerIp = peerIp
                    )
                    continue
                }

                Regex("(\\d+)%").find(l)?.let { match ->
                    val percent = match.groupValues[1].toIntOrNull() ?: 0
                    _state.value = CrocTransferState.Transferring(
                        fileName = currentFileName,
                        currentFile = fileNames.indexOf(currentFileName).coerceAtLeast(0) + 1,
                        totalFiles = totalFilesFromProgress.coerceAtLeast(fileNames.size).coerceAtLeast(1),
                        currentFilePercent = percent,
                        bytesTransferred = totalBytes * percent / 100,
                        totalBytes = totalBytes.coerceAtLeast(1),
                        peerIp = peerIp
                    )
                }
            }

            val receivedText = if (isTextTransfer && receivedTextLines.isNotEmpty()) {
                receivedTextLines.joinToString("\n")
            } else null
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = outputTail.toList(),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress,
                receivedText = receivedText
            )
        } catch (e: InterruptedIOException) {
            if (_state.value is CrocTransferState.Cancelled || !coroutineContext.isActive) {
                Log.i(TAG, "croc output interrupted during cancellation")
            } else {
                Log.w(TAG, "croc output stream interrupted; using process exit state", e)
            }
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = if (outputTail.isEmpty()) {
                    listOf(e.message ?: "Stream interrupted")
                } else {
                    outputTail.toList()
                },
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            return ProcessResult(
                exitCode = -1,
                fileNames = fileNames,
                totalBytes = totalBytes,
                outputTail = listOf(e.message ?: "Unknown error"),
                peerIp = peerIp,
                totalFileCount = totalFilesFromProgress
            )
        }
    }

    private fun parseSize(num: Double, unit: String): Long {
        return when (unit.lowercase()) {
            "b" -> num.toLong()
            "kb" -> (num * 1024).toLong()
            "mb" -> (num * 1024 * 1024).toLong()
            "gb" -> (num * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }
}

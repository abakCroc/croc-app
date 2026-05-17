package com.dking.crocapp.croc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BinarySetupPhase {
    Idle,
    Loading,
    Ready,
    Error
}

data class BinarySetupState(
    val phase: BinarySetupPhase = BinarySetupPhase.Idle,
    val title: String = "Preparing croc",
    val detail: String = "Loading the transfer engine.",
    val errorMessage: String? = null
)

class CrocBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "CrocBinaryManager"
    }

    private val _setupState = MutableStateFlow(BinarySetupState())
    val setupState: StateFlow<BinarySetupState> = _setupState.asStateFlow()

    fun isBinaryReady(): Boolean = try {
        croc.Croc.touch()
        true
    } catch (_: Exception) { false }

    fun initialize(): Boolean {
        return try {
            _setupState.value = BinarySetupState(
                phase = BinarySetupPhase.Loading,
                title = "Loading croc",
                detail = "Loading the transfer engine."
            )
            croc.Croc.touch()
            _setupState.value = BinarySetupState(
                phase = BinarySetupPhase.Ready,
                title = "croc is ready",
                detail = "Transfer engine loaded and ready."
            )
            Log.i(TAG, "Croc binary ready: true")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Binary initialization failed", e)
            _setupState.value = BinarySetupState(
                phase = BinarySetupPhase.Error,
                title = "Setup needs attention",
                detail = "Failed to load the transfer engine.",
                errorMessage = e.message ?: "Unknown error"
            )
            false
        }
    }
}

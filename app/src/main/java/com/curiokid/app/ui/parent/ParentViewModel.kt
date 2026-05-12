package com.curiokid.app.ui.parent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.curiokid.app.CurioKidApplication
import com.curiokid.app.ai.LunaAI
import com.curiokid.app.data.local.QuestionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

data class ParentUiState(
    val unlocked: Boolean = false,
    val needsPinSetup: Boolean = false,
    val pinError: String? = null,
    val items: List<QuestionEntity> = emptyList(),
    val digest: String? = null,
    val isGeneratingDigest: Boolean = false,
    val digestError: String? = null,
)

class ParentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CurioKidApplication
    private val settings = app.settings
    private val repo = app.repository

    private val _state = MutableStateFlow(
        ParentUiState(needsPinSetup = settings.parentPin.value.isNullOrBlank())
    )
    val state: StateFlow<ParentUiState> = _state.asStateFlow()

    fun submitPin(input: String) {
        if (settings.parentPin.value.isNullOrBlank()) {
            if (input.length == 4 && input.all { it.isDigit() }) {
                settings.setParentPin(input)
                unlock()
            } else {
                _state.update { it.copy(pinError = "Pick a 4-digit PIN") }
            }
            return
        }
        if (settings.verifyPin(input)) {
            unlock()
        } else {
            _state.update { it.copy(pinError = "Wrong PIN. Try again.") }
        }
    }

    private fun unlock() {
        _state.update { it.copy(unlocked = true, pinError = null, needsPinSetup = false) }
        loadToday()
    }

    fun lock() {
        _state.update {
            it.copy(
                unlocked = false,
                items = emptyList(),
                digest = null,
                pinError = null,
                needsPinSetup = settings.parentPin.value.isNullOrBlank(),
            )
        }
    }

    private fun loadToday() {
        viewModelScope.launch {
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val items = repo.forSince(start)
            _state.update { it.copy(items = items) }
        }
    }

    fun generateDigest() {
        val items = _state.value.items
        if (items.isEmpty()) {
            _state.update { it.copy(digest = "No questions today yet.") }
            return
        }
        val provider = settings.provider.value
        val key = settings.activeApiKey.value
        if (provider != com.curiokid.app.ai.provider.LlmProvider.LOCAL && key.isNullOrBlank()) {
            _state.update {
                it.copy(digestError = "Add an API key for ${provider.displayName} in Settings to enable digests.")
            }
            return
        }
        _state.update { it.copy(isGeneratingDigest = true, digestError = null) }
        viewModelScope.launch {
            val ai = LunaAI(
                provider = provider,
                apiKey = key,
                modelName = settings.activeModel.value,
            )
            val raw = formatHistoryForDigest(items)
            val result = ai.summariseForParent(raw)
            val debug = settings.debugMode.value
            _state.update { current ->
                current.copy(
                    isGeneratingDigest = false,
                    digest = result.getOrNull(),
                    digestError = result.exceptionOrNull()
                        ?.let { LunaAI.friendlyError(it, debug = debug) },
                )
            }
        }
    }

    private fun formatHistoryForDigest(items: List<QuestionEntity>): String {
        val df = DateFormat.getTimeInstance(DateFormat.SHORT)
        return buildString {
            appendLine("Date: ${DateFormat.getDateInstance().format(Date())}")
            appendLine("Total questions: ${items.size}")
            appendLine()
            items.asReversed().forEachIndexed { index, q ->
                appendLine("${index + 1}. [${df.format(Date(q.timestamp))}] Child asked: ${q.question}")
                appendLine("   Luna replied: ${q.answer.take(280)}")
            }
        }
    }
}

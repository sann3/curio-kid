package com.curiokid.app.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.curiokid.app.CurioKidApplication
import com.curiokid.app.ai.LunaAI
import com.curiokid.app.ai.provider.ChatTurn
import com.curiokid.app.ai.provider.LlmProvider
import com.curiokid.app.data.debug.DebugLog
import com.curiokid.app.data.local.QuestionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: Role,
    val text: String,
    val image: Bitmap? = null,
    val isLoading: Boolean = false,
) {
    enum class Role { USER, LUNA }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val pendingImage: Bitmap? = null,
    val error: String? = null,
    val needsApiKey: Boolean = false,
    val provider: LlmProvider = LlmProvider.DEFAULT,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CurioKidApplication
    private val settings = app.settings
    private val repo = app.repository

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        combine(settings.provider, settings.activeApiKey) { provider, key ->
            provider to key
        }
            .onEach { (provider, key) ->
                _state.update {
                    it.copy(
                        provider = provider,
                        // Local doesn't need a key, so it's always "ready" here;
                        // if local is selected the actual not-ready error is
                        // surfaced by LunaAI.friendlyError when ask() is called.
                        needsApiKey = provider != LlmProvider.LOCAL && key.isNullOrBlank(),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun attachImage(bitmap: Bitmap) {
        _state.update { it.copy(pendingImage = bitmap, error = null) }
    }

    fun clearAttachment() {
        _state.update { it.copy(pendingImage = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun ask(rawText: String) {
        val text = rawText.trim()
        val image = _state.value.pendingImage
        if (text.isBlank() && image == null) return

        val provider = settings.provider.value
        val key = settings.activeApiKey.value
        if (provider != LlmProvider.LOCAL && key.isNullOrBlank()) {
            _state.update { it.copy(needsApiKey = true) }
            return
        }

        // Snapshot the conversation BEFORE adding the new user/placeholder
        // turns so we can hand it to the backend as context. We cap it so a
        // long session doesn't balloon the request size — Gemma chat
        // contexts are bounded and we already pay for every prior token.
        val history = _state.value.messages.toChatHistory(maxTurns = MAX_HISTORY_TURNS)

        val userMessage = ChatMessage(
            role = ChatMessage.Role.USER,
            text = text.ifBlank { "(picture)" },
            image = image,
        )
        val placeholder = ChatMessage(
            role = ChatMessage.Role.LUNA,
            text = "Thinking…",
            isLoading = true,
        )
        _state.update {
            it.copy(
                messages = it.messages + userMessage + placeholder,
                pendingImage = null,
                isSending = true,
                error = null,
            )
        }

        viewModelScope.launch {
            val modelName = settings.activeModel.value
            val kidAge = settings.kidAge.value
            DebugLog.i(
                "ChatVM",
                "ask via ${provider.displayName} model=$modelName kidAge=$kidAge image=${image != null} historyTurns=${history.size}"
            )
            val ai = LunaAI(
                provider = provider,
                apiKey = key,
                modelName = modelName,
                kidAge = kidAge,
                localEngine = app.localGemmaEngine,
                localManager = app.localModelManager,
            )
            val result = ai.ask(text, image, history)
            val debug = settings.debugMode.value
            val answer = result.getOrElse { e -> LunaAI.friendlyError(e, debug = debug) }
            _state.update { current ->
                current.copy(
                    isSending = false,
                    messages = current.messages.dropLast(1) + ChatMessage(
                        role = ChatMessage.Role.LUNA,
                        text = answer,
                    ),
                    error = null,
                )
            }
            repo.save(
                QuestionEntity(
                    timestamp = System.currentTimeMillis(),
                    question = text.ifBlank { "(picture)" },
                    answer = answer,
                    attachmentType = if (image != null) "image" else "none",
                )
            )
        }
    }

    companion object {
        /**
         * Roughly the last 8 user/Luna exchanges. Enough for natural
         * follow-ups ("tell me more", "why?") without sending the whole
         * day's chat back to the model on every turn.
         */
        private const val MAX_HISTORY_TURNS = 16
    }
}

/**
 * Convert the visible chat into the role-tagged list the LLM backends
 * expect. Skips the in-flight "Thinking…" placeholder, blank messages,
 * and trims to the most recent `maxTurns` so token usage stays bounded.
 * Image-only past turns become a short text marker so the model still
 * knows the child sent something visual earlier.
 */
private fun List<ChatMessage>.toChatHistory(maxTurns: Int): List<ChatTurn> {
    val turns = mapNotNull { msg ->
        if (msg.isLoading) return@mapNotNull null
        val text = msg.text.trim()
        if (text.isEmpty()) return@mapNotNull null
        val role = when (msg.role) {
            ChatMessage.Role.USER -> ChatTurn.Role.USER
            ChatMessage.Role.LUNA -> ChatTurn.Role.ASSISTANT
        }
        ChatTurn(role = role, text = text)
    }
    return if (turns.size <= maxTurns) turns else turns.takeLast(maxTurns)
}

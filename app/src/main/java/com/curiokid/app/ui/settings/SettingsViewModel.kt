package com.curiokid.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.curiokid.app.CurioKidApplication
import com.curiokid.app.ai.local.LocalGemmaCatalog
import com.curiokid.app.ai.local.LocalModelState
import com.curiokid.app.ai.provider.LlmProvider
import com.curiokid.app.data.debug.DebugLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CurioKidApplication
    private val settings = app.settings
    private val localManager = app.localModelManager

    val provider: StateFlow<LlmProvider> = settings.provider

    val googleApiKey: StateFlow<String?> = settings.googleApiKey
    val openRouterApiKey: StateFlow<String?> = settings.openRouterApiKey
    val huggingFaceToken: StateFlow<String?> = settings.huggingFaceToken

    val googleModel: StateFlow<String> = settings.googleModel
    val openRouterModel: StateFlow<String> = settings.openRouterModel
    val localModel: StateFlow<String> = settings.localModel

    val parentPin: StateFlow<String?> = settings.parentPin

    val debugMode: StateFlow<Boolean> = settings.debugMode
    val debugEntries: StateFlow<List<DebugLog.Entry>> = DebugLog.entries

    val kidAge: StateFlow<Int> = settings.kidAge
    val kidAgeOptions: List<Int> = com.curiokid.app.data.settings.SettingsManager.KID_AGE_OPTIONS

    /**
     * Catalog entry currently selected in the Settings model picker.
     * Falls back to [LocalGemmaCatalog.DEFAULT] if persisted ID has been
     * removed from the catalog (e.g. after an app update).
     */
    val selectedLocalVariant: StateFlow<LocalGemmaCatalog.Variant> =
        settings.localModel
            .map { id -> LocalGemmaCatalog.byId(id) ?: LocalGemmaCatalog.DEFAULT }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                LocalGemmaCatalog.byId(settings.localModel.value) ?: LocalGemmaCatalog.DEFAULT,
            )

    /**
     * Install / download state for whichever variant is currently
     * selected. Switching the picker chip re-subscribes to the new
     * variant's flow without the consumer needing to rewire anything.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val localModelState: StateFlow<LocalModelState> = selectedLocalVariant
        .flatMapLatest { variant -> localManager.stateFor(variant) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            localManager.stateFor(selectedLocalVariant.value).value,
        )

    private val _meteredConfirmation = MutableStateFlow<LocalGemmaCatalog.Variant?>(null)
    /**
     * Set to a non-null variant when the user tapped Download on a
     * metered network and we want the UI to surface a confirmation
     * dialog. Cleared by [confirmMeteredDownload] / [dismissMeteredDownload].
     */
    val meteredConfirmation: StateFlow<LocalGemmaCatalog.Variant?> =
        _meteredConfirmation.asStateFlow()

    fun modelsFor(provider: LlmProvider): List<String> = settings.modelsFor(provider)

    fun setProvider(value: LlmProvider) = settings.setProvider(value)

    fun saveGoogleApiKey(value: String) = settings.setGoogleApiKey(value)
    fun clearGoogleApiKey() = settings.setGoogleApiKey(null)

    fun saveOpenRouterApiKey(value: String) = settings.setOpenRouterApiKey(value)
    fun clearOpenRouterApiKey() = settings.setOpenRouterApiKey(null)

    fun saveHuggingFaceToken(value: String) = settings.setHuggingFaceToken(value)
    fun clearHuggingFaceToken() = settings.setHuggingFaceToken(null)

    fun setGoogleModel(value: String) = settings.setGoogleModel(value)
    fun setOpenRouterModel(value: String) = settings.setOpenRouterModel(value)
    fun setLocalModel(value: String) = settings.setLocalModel(value)

    fun setPin(value: String?) = settings.setParentPin(value)

    fun setDebugMode(enabled: Boolean) = settings.setDebugMode(enabled)

    fun setKidAge(age: Int) = settings.setKidAge(age)

    fun clearDebugLog() = DebugLog.clear()

    /**
     * Start (or resume) downloading the currently-selected on-device
     * variant. If the device is on a metered connection, kicks off the
     * confirmation dialog instead and waits for [confirmMeteredDownload].
     */
    fun downloadSelectedLocalModel() {
        val variant = selectedLocalVariant.value
        if (localManager.isOnMeteredNetwork()) {
            _meteredConfirmation.value = variant
        } else {
            localManager.download(variant)
        }
    }

    fun confirmMeteredDownload() {
        val variant = _meteredConfirmation.value ?: return
        _meteredConfirmation.value = null
        localManager.download(variant)
    }

    fun dismissMeteredDownload() {
        _meteredConfirmation.value = null
    }

    fun cancelSelectedLocalModelDownload() {
        localManager.cancel(selectedLocalVariant.value)
    }

    fun deleteSelectedLocalModel() {
        viewModelScope.launch {
            localManager.delete(selectedLocalVariant.value)
        }
    }
}

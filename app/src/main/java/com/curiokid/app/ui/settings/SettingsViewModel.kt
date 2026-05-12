package com.curiokid.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.curiokid.app.CurioKidApplication
import com.curiokid.app.ai.provider.LlmProvider
import com.curiokid.app.data.debug.DebugLog
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = (application as CurioKidApplication).settings

    val provider: StateFlow<LlmProvider> = settings.provider

    val googleApiKey: StateFlow<String?> = settings.googleApiKey
    val openRouterApiKey: StateFlow<String?> = settings.openRouterApiKey

    val googleModel: StateFlow<String> = settings.googleModel
    val openRouterModel: StateFlow<String> = settings.openRouterModel
    val localModel: StateFlow<String> = settings.localModel

    val parentPin: StateFlow<String?> = settings.parentPin

    val debugMode: StateFlow<Boolean> = settings.debugMode
    val debugEntries: StateFlow<List<DebugLog.Entry>> = DebugLog.entries

    fun modelsFor(provider: LlmProvider): List<String> = settings.modelsFor(provider)

    fun setProvider(value: LlmProvider) = settings.setProvider(value)

    fun saveGoogleApiKey(value: String) = settings.setGoogleApiKey(value)
    fun clearGoogleApiKey() = settings.setGoogleApiKey(null)

    fun saveOpenRouterApiKey(value: String) = settings.setOpenRouterApiKey(value)
    fun clearOpenRouterApiKey() = settings.setOpenRouterApiKey(null)

    fun setGoogleModel(value: String) = settings.setGoogleModel(value)
    fun setOpenRouterModel(value: String) = settings.setOpenRouterModel(value)
    fun setLocalModel(value: String) = settings.setLocalModel(value)

    fun setPin(value: String?) = settings.setParentPin(value)

    fun setDebugMode(enabled: Boolean) = settings.setDebugMode(enabled)

    fun clearDebugLog() = DebugLog.clear()
}

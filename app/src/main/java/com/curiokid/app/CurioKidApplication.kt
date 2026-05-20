package com.curiokid.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.curiokid.app.ai.local.LocalGemmaEngine
import com.curiokid.app.ai.local.LocalModelManager
import com.curiokid.app.data.local.AppDatabase
import com.curiokid.app.data.repository.QuestionRepository
import com.curiokid.app.data.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CurioKidApplication : Application() {

    lateinit var settings: SettingsManager
        private set

    lateinit var repository: QuestionRepository
        private set

    lateinit var localModelManager: LocalModelManager
        private set

    lateinit var localGemmaEngine: LocalGemmaEngine
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        repository = QuestionRepository(AppDatabase.get(this).questionDao())
        localModelManager = LocalModelManager(
            context = this,
            tokenProvider = { settings.huggingFaceToken.value },
        )
        localGemmaEngine = LocalGemmaEngine(this)
    }

    /**
     * Drop the multi-GB on-device model when the OS asks for memory back.
     * The engine reloads on the next ask if local is still selected, so
     * the user never sees an error — just the usual "first answer takes a
     * moment" warm-up.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE && this::localGemmaEngine.isInitialized) {
            applicationScope.launch { localGemmaEngine.unload() }
        }
    }
}

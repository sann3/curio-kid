package com.curiokid.app

import android.app.Application
import com.curiokid.app.data.local.AppDatabase
import com.curiokid.app.data.repository.QuestionRepository
import com.curiokid.app.data.settings.SettingsManager

class CurioKidApplication : Application() {

    lateinit var settings: SettingsManager
        private set

    lateinit var repository: QuestionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        repository = QuestionRepository(AppDatabase.get(this).questionDao())
    }
}

package com.andyluu.debrief

import android.app.Application
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.FolderRepository
import com.andyluu.debrief.data.SearchRepository
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.SettingsStore
import com.andyluu.debrief.data.SidecarStore
import com.andyluu.debrief.data.UsageStore
import com.andyluu.debrief.transcription.UsageRepository
import com.andyluu.debrief.ai.AiPassProcessor
import com.andyluu.debrief.ai.RecordingRenamer

class AppServices(application: Application) {
    val database = DebriefDatabase.get(application)
    val settings = SettingsStore(application)
    val secrets = SecureSecretStore(application)
    val usage = UsageStore(application)
    val usageRepository = UsageRepository(usage)
    val search = SearchRepository(database)
    val sidecars = SidecarStore(application, database, search)
    val renamer = RecordingRenamer(application, database.dao())
    val aiPass = AiPassProcessor(application, database, settings, secrets, search, sidecars, renamer)
    val folders = FolderRepository(application, database.dao(), sidecars)
}

class DebriefApplication : Application() {
    val services by lazy { AppServices(this) }
}

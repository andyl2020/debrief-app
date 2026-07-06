package com.andyluu.debrief

import android.app.Application
import com.andyluu.debrief.data.DebriefDatabase
import com.andyluu.debrief.data.FolderRepository
import com.andyluu.debrief.data.SearchRepository
import com.andyluu.debrief.data.SecureSecretStore
import com.andyluu.debrief.data.SettingsStore
import com.andyluu.debrief.data.SidecarStore

class AppServices(application: Application) {
    val database = DebriefDatabase.get(application)
    val settings = SettingsStore(application)
    val secrets = SecureSecretStore(application)
    val search = SearchRepository(database)
    val sidecars = SidecarStore(application, database, search)
    val folders = FolderRepository(application, database.dao(), sidecars)
}

class DebriefApplication : Application() {
    val services by lazy { AppServices(this) }
}

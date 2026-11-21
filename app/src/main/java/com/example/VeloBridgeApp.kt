package com.example

import android.app.Application
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsDataStore
import com.example.data.repository.ProfileRepository

class VeloBridgeApp : Application() {
    lateinit var repository: ProfileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getInstance(this)
        val dataStore = SettingsDataStore(this)
        repository = ProfileRepository(
            profileDao = database.profileDao(),
            settingsDataStore = dataStore
        )
    }
}

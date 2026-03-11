package com.musichub

import android.app.Application
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.repository.MusicRepository

class MusicHubApplication : Application() {

    val database: MusicHubDatabase by lazy {
        MusicHubDatabase.getDatabase(this)
    }

    val repository: MusicRepository by lazy {
        MusicRepository(database)
    }

    companion object {
        private lateinit var instance: MusicHubApplication

        fun getInstance(): MusicHubApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}

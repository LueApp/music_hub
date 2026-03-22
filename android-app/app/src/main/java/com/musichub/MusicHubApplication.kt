package com.musichub

import android.app.Application
import com.musichub.data.local.MusicHubDatabase
import com.musichub.data.repository.MusicRepository
import com.musichub.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        initSyncScheduler()
    }

    private fun initSyncScheduler() {
        CoroutineScope(Dispatchers.IO).launch {
            val syncedPlaylistIds = repository.getAllSyncedPlaylistIds()
            if (syncedPlaylistIds.isNotEmpty()) {
                SyncScheduler.schedulePeriodicSync(this@MusicHubApplication)
            }
        }
    }
}

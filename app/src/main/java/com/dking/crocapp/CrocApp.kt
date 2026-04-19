package com.dking.crocapp

import android.app.Application
import android.util.Log
import com.dking.crocapp.croc.CrocBinaryManager
import com.dking.crocapp.data.db.AppDatabase
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CrocApp : Application() {

    companion object {
        private const val TAG = "CrocApp"
    }

    lateinit var database: AppDatabase
        private set

    lateinit var binaryManager: CrocBinaryManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        binaryManager = CrocBinaryManager(this)

        appScope.launch {
            UserPreferencesRepository(this@CrocApp).ensureDefaultCodePhrase()
        }

        appScope.launch {
            val ready = binaryManager.initialize()
            Log.i(TAG, "Croc binary ready: $ready")
        }
    }
}

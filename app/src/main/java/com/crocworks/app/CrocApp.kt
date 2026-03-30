package com.crocworks.app

import android.app.Application
import android.util.Log
import com.crocworks.app.croc.CrocBinaryManager
import com.crocworks.app.data.db.AppDatabase

class CrocApp : Application() {

    companion object {
        private const val TAG = "CrocApp"
    }

    lateinit var database: AppDatabase
        private set

    lateinit var binaryManager: CrocBinaryManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        binaryManager = CrocBinaryManager(this)

        // Eagerly check binary availability
        val ready = binaryManager.initialize()
        Log.i(TAG, "Croc binary ready: $ready")
        if (ready) {
            val version = binaryManager.getVersion()
            Log.i(TAG, "Croc version: $version")
        }
    }
}

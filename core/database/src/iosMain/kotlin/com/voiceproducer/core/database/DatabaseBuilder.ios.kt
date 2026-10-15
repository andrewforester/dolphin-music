package com.voiceproducer.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSHomeDirectory

@OptIn(ExperimentalForeignApi::class)
fun buildAppDatabase(): AppDatabase =
    Room
        .databaseBuilder<AppDatabase>(name = NSHomeDirectory() + "/Documents/${AppDatabase.NAME}")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

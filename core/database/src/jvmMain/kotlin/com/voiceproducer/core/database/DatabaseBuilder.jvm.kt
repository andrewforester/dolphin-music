package com.voiceproducer.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

fun buildAppDatabase(file: File = File(System.getProperty("user.home"), ".voiceproducer/${AppDatabase.NAME}")): AppDatabase {
    file.parentFile?.mkdirs()
    return Room
        .databaseBuilder<AppDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

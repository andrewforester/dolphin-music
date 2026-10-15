package com.voiceproducer.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

fun buildAppDatabase(
    context: Context,
    file: File = File(context.applicationContext.filesDir, AppDatabase.NAME),
): AppDatabase =
    Room
        .databaseBuilder<AppDatabase>(
            context = context.applicationContext,
            name = file.absolutePath,
        ).setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

package com.voiceproducer.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseNameTest {
    @Test
    fun databaseFileNameIsUnchanged() {
        assertEquals("voiceproducer.db", AppDatabase.NAME)
    }
}

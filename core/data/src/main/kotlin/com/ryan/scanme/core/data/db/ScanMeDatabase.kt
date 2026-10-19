package com.ryan.scanme.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DocumentEntity::class, PageEntity::class], version = 1, exportSchema = false)
abstract class ScanMeDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
}

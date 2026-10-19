package com.ryan.scanme.core.data.di

import android.content.Context
import androidx.room.Room
import com.ryan.scanme.core.data.db.DocumentDao
import com.ryan.scanme.core.data.db.ScanMeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ScanMeDatabase =
        Room.databaseBuilder(context, ScanMeDatabase::class.java, "scanme.db").build()

    @Provides
    fun documentDao(db: ScanMeDatabase): DocumentDao = db.documentDao()
}

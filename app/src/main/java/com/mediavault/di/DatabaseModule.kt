package com.mediavault.di

import android.content.Context
import androidx.room.Room
import com.mediavault.core.database.AppDatabase
import com.mediavault.core.database.ManagedFileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mediavault.db")
            // Pre-release only: v1 held just the media index (files stay on disk). Replace with a real
            // Migration before shipping to users.
            .fallbackToDestructiveMigrationFrom(1, 2)
            .build()

    @Provides
    fun destinationDao(db: AppDatabase): com.mediavault.core.database.DestinationDao = db.destinationDao()

    @Provides
    fun ruleDao(db: AppDatabase): com.mediavault.core.database.RuleDao = db.ruleDao()

    @Provides
    fun uploadJobDao(db: AppDatabase): com.mediavault.core.database.UploadJobDao = db.uploadJobDao()

    @Provides
    fun managedFileDao(db: AppDatabase): ManagedFileDao = db.managedFileDao()
}

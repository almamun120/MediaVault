package com.mediavault.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ManagedFileEntity::class, DestinationEntity::class, RuleEntity::class, UploadJobEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun managedFileDao(): ManagedFileDao
    abstract fun destinationDao(): DestinationDao
    abstract fun ruleDao(): RuleDao
    abstract fun uploadJobDao(): UploadJobDao
}

package com.mediavault.di

import com.mediavault.transfer.UploadScheduler
import com.mediavault.transfer.WorkManagerUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferModule {
    @Binds @Singleton
    abstract fun scheduler(impl: WorkManagerUploadScheduler): UploadScheduler
}

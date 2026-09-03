package com.daniel.tvdeinsight.di

import com.daniel.tvdeinsight.data.repository.DataStoreSettingsRepository
import android.content.Context
import androidx.room.Room
import com.daniel.tvdeinsight.data.local.AppDatabase
import com.daniel.tvdeinsight.data.repository.OfferAnalysisStore
import com.daniel.tvdeinsight.data.repository.RoomOfferAnalysisStore
import com.daniel.tvdeinsight.data.repository.SettingsRepository
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    companion object {
        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tvde_insight.db")
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .addMigrations(AppDatabase.MIGRATION_2_3)
                .build()
    }

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        implementation: DataStoreSettingsRepository
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindOfferAnalysisStore(
        implementation: RoomOfferAnalysisStore
    ): OfferAnalysisStore
}

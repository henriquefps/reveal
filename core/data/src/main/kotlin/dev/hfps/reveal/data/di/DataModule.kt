package dev.hfps.reveal.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.db.MetaDao
import dev.hfps.reveal.data.db.RevealDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RevealDatabase =
        Room.databaseBuilder(context, RevealDatabase::class.java, RevealDatabase.NAME).build()

    @Provides
    fun provideMetaDao(database: RevealDatabase): MetaDao = database.metaDao()
}

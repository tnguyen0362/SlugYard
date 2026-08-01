package com.sluggyard.tv.core.di

import com.sluggyard.tv.data.repository.AddonRepositoryImpl
import com.sluggyard.tv.data.repository.CatalogRepositoryImpl
import com.sluggyard.tv.data.repository.LibraryRepositoryImpl
import com.sluggyard.tv.data.repository.MetaRepositoryImpl
import com.sluggyard.tv.data.repository.StreamRepositoryImpl
import com.sluggyard.tv.data.repository.SubtitleRepositoryImpl
import com.sluggyard.tv.data.repository.WatchProgressRepositoryImpl
import com.sluggyard.tv.domain.repository.AddonRepository
import com.sluggyard.tv.domain.repository.CatalogRepository
import com.sluggyard.tv.domain.repository.LibraryRepository
import com.sluggyard.tv.domain.repository.MetaRepository
import com.sluggyard.tv.domain.repository.StreamRepository
import com.sluggyard.tv.domain.repository.SubtitleRepository
import com.sluggyard.tv.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository
}

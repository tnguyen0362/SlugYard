package com.sluggyard.tv.core.di

import com.sluggyard.tv.ui.app.settings.DefaultSettingsFacade
import com.sluggyard.tv.ui.app.settings.SettingsFacade
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    @Singleton
    abstract fun bindSettingsFacade(
        facade: DefaultSettingsFacade,
    ): SettingsFacade
}

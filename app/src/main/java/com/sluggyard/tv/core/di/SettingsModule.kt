package com.sluggyard.tv.core.di

import com.sluggyard.tv.ui.rewrite.settings.DefaultRewriteSettingsFacade
import com.sluggyard.tv.ui.rewrite.settings.RewriteSettingsFacade
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
    abstract fun bindRewriteSettingsFacade(
        facade: DefaultRewriteSettingsFacade,
    ): RewriteSettingsFacade
}

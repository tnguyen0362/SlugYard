package com.sluggyard.tv.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.sluggyard.tv.BuildConfig
import com.sluggyard.tv.core.network.IPv4FirstDns
import com.sluggyard.tv.core.sync.DataStoreLocalSyncStore
import com.sluggyard.tv.core.sync.LocalSyncStore
import com.sluggyard.tv.core.sync.SyncCoordinator
import com.sluggyard.tv.core.sync.SyncMutationRecorder
import com.sluggyard.tv.core.sync.SyncSchemaValidator
import com.sluggyard.tv.core.sync.DefaultSupabaseSchemaProbe
import com.sluggyard.tv.core.sync.ProgressSyncBridge
import com.sluggyard.tv.core.sync.LibraryWatchSyncBridge
import com.sluggyard.tv.core.sync.ProviderCredentialSyncBridge
import com.sluggyard.tv.core.sync.auth.DataStoreSupabaseSessionStore
import com.sluggyard.tv.core.sync.auth.DefaultSupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.core.sync.remote.DefaultSupabaseDataGateway
import com.sluggyard.tv.core.sync.remote.DefaultSupabaseCredentialVaultGateway
import com.sluggyard.tv.core.sync.remote.SupabaseCredentialVaultGateway
import com.sluggyard.tv.core.sync.remote.SupabaseDataGateway
import com.sluggyard.tv.core.sync.remote.SupabaseHttpTransport
import com.sluggyard.tv.data.local.DebridSettingsDataStore
import com.sluggyard.tv.ui.app.data.APP_DATA_STORE_NAME
import com.sluggyard.tv.ui.app.data.DataStorePlaybackProgressSyncBridge
import com.sluggyard.tv.ui.app.data.LibraryWatchStore
import com.sluggyard.tv.ui.app.data.ProfileStore
import com.sluggyard.tv.ui.app.data.LibraryWatchRepository
import com.sluggyard.tv.ui.app.data.migrateLegacyAppDataStoreFiles
import com.sluggyard.tv.ui.app.debrid.DataStoreProviderCredentialSyncBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    @Named("supabase")
    fun provideSupabaseOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideSupabaseHttpTransport(
        @Named("supabase") client: OkHttpClient,
    ): SupabaseHttpTransport = SupabaseHttpTransport(
        client = client,
        baseUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
    )

    @Provides
    @Singleton
    fun provideSupabaseSessionStore(
        @ApplicationContext context: Context,
    ): SupabaseSessionStore = DataStoreSupabaseSessionStore(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("slugyard_supabase_session_v1") },
        ),
    )

    @Provides
    @Singleton
    fun provideSupabaseAuthGateway(
        transport: SupabaseHttpTransport,
        sessions: SupabaseSessionStore,
    ): SupabaseAuthGateway = DefaultSupabaseAuthGateway(transport, sessions)

    @Provides
    @Singleton
    fun provideSupabaseCredentialVaultGateway(
        transport: SupabaseHttpTransport,
        sessions: SupabaseSessionStore,
        auth: SupabaseAuthGateway,
    ): SupabaseCredentialVaultGateway = DefaultSupabaseCredentialVaultGateway(transport, sessions, auth)

    @Provides
    @Singleton
    fun provideSupabaseDataGateway(
        transport: SupabaseHttpTransport,
        sessions: SupabaseSessionStore,
        auth: SupabaseAuthGateway,
        credentialVault: SupabaseCredentialVaultGateway,
    ): SupabaseDataGateway = DefaultSupabaseDataGateway(transport, sessions, auth, credentialVault)

    @Provides
    @Singleton
    fun provideLocalSyncStore(
        @ApplicationContext context: Context,
    ): LocalSyncStore = DataStoreLocalSyncStore(context)

    @Provides
    @Singleton
    fun provideProviderCredentialSyncBridge(
        @Named("app") dataStore: DataStore<Preferences>,
        mutationRecorder: SyncMutationRecorder,
        debridSettings: DebridSettingsDataStore,
    ): ProviderCredentialSyncBridge = DataStoreProviderCredentialSyncBridge(
        dataStore = dataStore,
        mutationRecorder = mutationRecorder,
        debridSettings = debridSettings,
    )

    @Provides
    @Singleton
    fun provideSyncCoordinator(
        sessions: SupabaseSessionStore,
        remote: SupabaseDataGateway,
        local: LocalSyncStore,
        progress: ProgressSyncBridge,
        libraryWatch: LibraryWatchSyncBridge,
        providerCredentials: ProviderCredentialSyncBridge,
    ): SyncCoordinator = SyncCoordinator(
        sessions,
        remote,
        local,
        progress = progress,
        libraryWatch = libraryWatch,
        providerCredentials = providerCredentials,
    )

    @Provides
    @Singleton
    fun provideSyncMutationRecorder(
        sessions: SupabaseSessionStore,
        local: LocalSyncStore,
    ): SyncMutationRecorder = SyncMutationRecorder(sessions, local)

    @Provides
    @Singleton
    @Named("app")
    fun provideAppDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> {
        // Must run before DataStore opens the file — otherwise TorBox keys stay orphaned
        // in playflix_rewrite after the playflix_app rename.
        migrateLegacyAppDataStoreFiles(context)
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(APP_DATA_STORE_NAME) },
        )
    }

    @Provides
    @Singleton
    fun provideProgressSyncBridge(
        @Named("app") dataStore: DataStore<Preferences>,
        mutationRecorder: SyncMutationRecorder,
    ): ProgressSyncBridge = DataStorePlaybackProgressSyncBridge(dataStore, mutationRecorder)

    @Provides
    @Singleton
    fun provideProfileStore(
        @Named("app") dataStore: DataStore<Preferences>,
    ): ProfileStore = ProfileStore(dataStore)

    @Provides
    @Singleton
    fun provideLibraryWatchStore(
        @ApplicationContext context: Context,
        @Named("app") dataStore: DataStore<Preferences>,
        profiles: ProfileStore,
        mutationRecorder: SyncMutationRecorder,
    ): LibraryWatchStore = LibraryWatchStore(
        dataStore = dataStore,
        profiles = profiles,
        mutationRecorder = mutationRecorder,
        appContext = context.applicationContext,
    )

    @Provides
    @Singleton
    fun provideLibraryWatchRepository(
        store: LibraryWatchStore,
    ): LibraryWatchRepository = store

    @Provides
    @Singleton
    fun provideLibraryWatchSyncBridge(
        store: LibraryWatchStore,
    ): LibraryWatchSyncBridge = store

    @Provides
    @Singleton
    fun provideSyncSchemaValidator(
        transport: SupabaseHttpTransport,
        sessions: SupabaseSessionStore,
        credentialVault: SupabaseCredentialVaultGateway,
    ): SyncSchemaValidator = SyncSchemaValidator(DefaultSupabaseSchemaProbe(transport, sessions, credentialVault))
}

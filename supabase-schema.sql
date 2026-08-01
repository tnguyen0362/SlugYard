-- SlugYard Supabase Schema
-- Complete database schema for user data sync

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================
-- TABLES
-- ============================================

-- User profiles
CREATE TABLE IF NOT EXISTS profiles (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_index INTEGER NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    avatar_color_hex TEXT NOT NULL DEFAULT '#1E88E5',
    uses_primary_addons BOOLEAN NOT NULL DEFAULT FALSE,
    uses_primary_plugins BOOLEAN NOT NULL DEFAULT FALSE,
    avatar_id TEXT,
    avatar_url TEXT,
    pin_hash TEXT,
    pin_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    pin_locked_until TIMESTAMPTZ,
    pin_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_index)
);

-- Addons per profile
CREATE TABLE IF NOT EXISTS addons (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    url TEXT NOT NULL,
    name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id, url)
);

-- Plugins per profile
CREATE TABLE IF NOT EXISTS plugins (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    url TEXT NOT NULL,
    name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    repo_type TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id, url)
);

-- Library items per profile
CREATE TABLE IF NOT EXISTS library (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    poster TEXT,
    poster_shape TEXT NOT NULL DEFAULT 'POSTER',
    background TEXT,
    description TEXT,
    release_info TEXT,
    imdb_rating REAL,
    genres JSONB DEFAULT '[]'::jsonb,
    addon_base_url TEXT,
    added_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id, content_id)
);

-- Watch progress
CREATE TABLE IF NOT EXISTS watch_progress (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    progress_key TEXT NOT NULL,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    video_id TEXT NOT NULL DEFAULT '',
    season INTEGER,
    episode INTEGER,
    position BIGINT NOT NULL DEFAULT 0,
    duration BIGINT NOT NULL DEFAULT 0,
    last_watched BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id, progress_key)
);

-- Watch progress events for delta sync
CREATE TABLE IF NOT EXISTS watch_progress_events (
    event_id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    operation TEXT NOT NULL,
    progress_key TEXT NOT NULL,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    video_id TEXT NOT NULL DEFAULT '',
    season INTEGER,
    episode INTEGER,
    position BIGINT NOT NULL DEFAULT 0,
    duration BIGINT NOT NULL DEFAULT 0,
    last_watched BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Watched items
CREATE TABLE IF NOT EXISTS watched_items (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    season INTEGER,
    episode INTEGER,
    watched_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id, content_id)
);

-- Watched items events for delta sync
CREATE TABLE IF NOT EXISTS watched_items_events (
    event_id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    operation TEXT NOT NULL,
    content_id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    season INTEGER,
    episode INTEGER,
    watched_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Collections (JSON blob)
CREATE TABLE IF NOT EXISTS collections (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    collections_json JSONB DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id)
);

-- Profile settings (JSON blob)
CREATE TABLE IF NOT EXISTS profile_settings (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    settings_json JSONB DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id)
);

-- Home catalog settings (JSON blob)
CREATE TABLE IF NOT EXISTS home_catalog_settings (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    profile_id INTEGER NOT NULL DEFAULT 1,
    settings_json JSONB DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, profile_id)
);

-- Provider credentials (Trakt, etc.)
CREATE TABLE IF NOT EXISTS provider_credentials (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    provider TEXT NOT NULL,
    credential_json JSONB DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, provider)
);

-- Linked devices for sync
CREATE TABLE IF NOT EXISTS linked_devices (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    owner_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    device_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    device_name TEXT,
    linked_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(owner_id, device_user_id)
);

-- Sync codes for device linking
CREATE TABLE IF NOT EXISTS sync_codes (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    owner_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    device_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Avatar catalog (public)
CREATE TABLE IF NOT EXISTS avatar_catalog (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    category TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    bg_color TEXT
);

-- Delta sync cursors
CREATE TABLE IF NOT EXISTS sync_cursors (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    cursor_type TEXT NOT NULL,
    cursor_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, cursor_type)
);

-- ============================================
-- INDEXES
-- ============================================

CREATE INDEX IF NOT EXISTS idx_profiles_user_id ON profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_addons_user_profile ON addons(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_plugins_user_profile ON plugins(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_library_user_profile ON library(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_library_content ON library(user_id, content_id);
CREATE INDEX IF NOT EXISTS idx_watch_progress_user_profile ON watch_progress(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_watch_progress_key ON watch_progress(user_id, progress_key);
CREATE INDEX IF NOT EXISTS idx_watch_progress_events_user ON watch_progress_events(user_id);
CREATE INDEX IF NOT EXISTS idx_watch_progress_events_created ON watch_progress_events(created_at);
CREATE INDEX IF NOT EXISTS idx_watched_items_user_profile ON watched_items(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_watched_items_events_user ON watched_items_events(user_id);
CREATE INDEX IF NOT EXISTS idx_watched_items_events_created ON watched_items_events(created_at);
CREATE INDEX IF NOT EXISTS idx_collections_user_profile ON collections(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_profile_settings_user_profile ON profile_settings(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_home_catalog_settings_user_profile ON home_catalog_settings(user_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_provider_credentials_user ON provider_credentials(user_id);
CREATE INDEX IF NOT EXISTS idx_linked_devices_owner ON linked_devices(owner_id);
CREATE INDEX IF NOT EXISTS idx_sync_codes_code ON sync_codes(code);
CREATE INDEX IF NOT EXISTS idx_sync_codes_expires ON sync_codes(expires_at);
CREATE INDEX IF NOT EXISTS idx_avatar_catalog_category ON avatar_catalog(category);
CREATE INDEX IF NOT EXISTS idx_avatar_catalog_sort ON avatar_catalog(sort_order);
CREATE INDEX IF NOT EXISTS idx_sync_cursors_user ON sync_cursors(user_id);

-- ============================================
-- ROW LEVEL SECURITY (RLS)
-- ============================================

-- Enable RLS on all tables
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE addons ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugins ENABLE ROW LEVEL SECURITY;
ALTER TABLE library ENABLE ROW LEVEL SECURITY;
ALTER TABLE watch_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE watch_progress_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE watched_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE watched_items_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE collections ENABLE ROW LEVEL SECURITY;
ALTER TABLE profile_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE home_catalog_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE provider_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE linked_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE avatar_catalog ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_cursors ENABLE ROW LEVEL SECURITY;

-- Private policies are created conditionally so a fresh bootstrap can be
-- retried without dropping or weakening an existing RLS policy.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profiles' AND policyname = 'Users can view own profiles') THEN
        CREATE POLICY "Users can view own profiles" ON profiles FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profiles' AND policyname = 'Users can insert own profiles') THEN
        CREATE POLICY "Users can insert own profiles" ON profiles FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profiles' AND policyname = 'Users can update own profiles') THEN
        CREATE POLICY "Users can update own profiles" ON profiles FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profiles' AND policyname = 'Users can delete own profiles') THEN
        CREATE POLICY "Users can delete own profiles" ON profiles FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'addons' AND policyname = 'Users can view own addons') THEN
        CREATE POLICY "Users can view own addons" ON addons FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'addons' AND policyname = 'Users can insert own addons') THEN
        CREATE POLICY "Users can insert own addons" ON addons FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'addons' AND policyname = 'Users can update own addons') THEN
        CREATE POLICY "Users can update own addons" ON addons FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'addons' AND policyname = 'Users can delete own addons') THEN
        CREATE POLICY "Users can delete own addons" ON addons FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'plugins' AND policyname = 'Users can view own plugins') THEN
        CREATE POLICY "Users can view own plugins" ON plugins FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'plugins' AND policyname = 'Users can insert own plugins') THEN
        CREATE POLICY "Users can insert own plugins" ON plugins FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'plugins' AND policyname = 'Users can update own plugins') THEN
        CREATE POLICY "Users can update own plugins" ON plugins FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'plugins' AND policyname = 'Users can delete own plugins') THEN
        CREATE POLICY "Users can delete own plugins" ON plugins FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'library' AND policyname = 'Users can view own library') THEN
        CREATE POLICY "Users can view own library" ON library FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'library' AND policyname = 'Users can insert own library') THEN
        CREATE POLICY "Users can insert own library" ON library FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'library' AND policyname = 'Users can update own library') THEN
        CREATE POLICY "Users can update own library" ON library FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'library' AND policyname = 'Users can delete own library') THEN
        CREATE POLICY "Users can delete own library" ON library FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress' AND policyname = 'Users can view own watch progress') THEN
        CREATE POLICY "Users can view own watch progress" ON watch_progress FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress' AND policyname = 'Users can insert own watch progress') THEN
        CREATE POLICY "Users can insert own watch progress" ON watch_progress FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress' AND policyname = 'Users can update own watch progress') THEN
        CREATE POLICY "Users can update own watch progress" ON watch_progress FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress' AND policyname = 'Users can delete own watch progress') THEN
        CREATE POLICY "Users can delete own watch progress" ON watch_progress FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress_events' AND policyname = 'Users can view own watch progress events') THEN
        CREATE POLICY "Users can view own watch progress events" ON watch_progress_events FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watch_progress_events' AND policyname = 'Users can insert own watch progress events') THEN
        CREATE POLICY "Users can insert own watch progress events" ON watch_progress_events FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items' AND policyname = 'Users can view own watched items') THEN
        CREATE POLICY "Users can view own watched items" ON watched_items FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items' AND policyname = 'Users can insert own watched items') THEN
        CREATE POLICY "Users can insert own watched items" ON watched_items FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items' AND policyname = 'Users can update own watched items') THEN
        CREATE POLICY "Users can update own watched items" ON watched_items FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items' AND policyname = 'Users can delete own watched items') THEN
        CREATE POLICY "Users can delete own watched items" ON watched_items FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items_events' AND policyname = 'Users can view own watched items events') THEN
        CREATE POLICY "Users can view own watched items events" ON watched_items_events FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'watched_items_events' AND policyname = 'Users can insert own watched items events') THEN
        CREATE POLICY "Users can insert own watched items events" ON watched_items_events FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'collections' AND policyname = 'Users can view own collections') THEN
        CREATE POLICY "Users can view own collections" ON collections FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'collections' AND policyname = 'Users can insert own collections') THEN
        CREATE POLICY "Users can insert own collections" ON collections FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'collections' AND policyname = 'Users can update own collections') THEN
        CREATE POLICY "Users can update own collections" ON collections FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'collections' AND policyname = 'Users can delete own collections') THEN
        CREATE POLICY "Users can delete own collections" ON collections FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profile_settings' AND policyname = 'Users can view own profile settings') THEN
        CREATE POLICY "Users can view own profile settings" ON profile_settings FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profile_settings' AND policyname = 'Users can insert own profile settings') THEN
        CREATE POLICY "Users can insert own profile settings" ON profile_settings FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profile_settings' AND policyname = 'Users can update own profile settings') THEN
        CREATE POLICY "Users can update own profile settings" ON profile_settings FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'profile_settings' AND policyname = 'Users can delete own profile settings') THEN
        CREATE POLICY "Users can delete own profile settings" ON profile_settings FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'home_catalog_settings' AND policyname = 'Users can view own home catalog settings') THEN
        CREATE POLICY "Users can view own home catalog settings" ON home_catalog_settings FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'home_catalog_settings' AND policyname = 'Users can insert own home catalog settings') THEN
        CREATE POLICY "Users can insert own home catalog settings" ON home_catalog_settings FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'home_catalog_settings' AND policyname = 'Users can update own home catalog settings') THEN
        CREATE POLICY "Users can update own home catalog settings" ON home_catalog_settings FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'home_catalog_settings' AND policyname = 'Users can delete own home catalog settings') THEN
        CREATE POLICY "Users can delete own home catalog settings" ON home_catalog_settings FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can view own provider credentials') THEN
        CREATE POLICY "Users can view own provider credentials" ON provider_credentials FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can insert own provider credentials') THEN
        CREATE POLICY "Users can insert own provider credentials" ON provider_credentials FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can update own provider credentials') THEN
        CREATE POLICY "Users can update own provider credentials" ON provider_credentials FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can delete own provider credentials') THEN
        CREATE POLICY "Users can delete own provider credentials" ON provider_credentials FOR DELETE USING (auth.uid() = user_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'linked_devices' AND policyname = 'Users can view own linked devices') THEN
        CREATE POLICY "Users can view own linked devices" ON linked_devices FOR SELECT USING (auth.uid() = owner_id OR auth.uid() = device_user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'linked_devices' AND policyname = 'Users can insert own linked devices') THEN
        CREATE POLICY "Users can insert own linked devices" ON linked_devices FOR INSERT WITH CHECK (auth.uid() = owner_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'linked_devices' AND policyname = 'Users can delete own linked devices') THEN
        CREATE POLICY "Users can delete own linked devices" ON linked_devices FOR DELETE USING (auth.uid() = owner_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_codes' AND policyname = 'Anyone can create sync codes') THEN
        CREATE POLICY "Anyone can create sync codes" ON sync_codes FOR INSERT WITH CHECK (auth.uid() = owner_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_codes' AND policyname = 'Users can view own sync codes') THEN
        CREATE POLICY "Users can view own sync codes" ON sync_codes FOR SELECT USING (auth.uid() = owner_id OR auth.uid() = device_user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_codes' AND policyname = 'Users can update own sync codes') THEN
        CREATE POLICY "Users can update own sync codes" ON sync_codes FOR UPDATE USING (auth.uid() = owner_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_codes' AND policyname = 'Users can delete own sync codes') THEN
        CREATE POLICY "Users can delete own sync codes" ON sync_codes FOR DELETE USING (auth.uid() = owner_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'avatar_catalog' AND policyname = 'Anyone can view avatar catalog') THEN
        CREATE POLICY "Anyone can view avatar catalog" ON avatar_catalog FOR SELECT USING (TRUE);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_cursors' AND policyname = 'Users can view own sync cursors') THEN
        CREATE POLICY "Users can view own sync cursors" ON sync_cursors FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_cursors' AND policyname = 'Users can insert own sync cursors') THEN
        CREATE POLICY "Users can insert own sync cursors" ON sync_cursors FOR INSERT WITH CHECK (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_cursors' AND policyname = 'Users can update own sync cursors') THEN
        CREATE POLICY "Users can update own sync cursors" ON sync_cursors FOR UPDATE USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'sync_cursors' AND policyname = 'Users can delete own sync cursors') THEN
        CREATE POLICY "Users can delete own sync cursors" ON sync_cursors FOR DELETE USING (auth.uid() = user_id);
    END IF;
END;
$$;

-- ============================================
-- TRIGGERS FOR DELTA SYNC
-- ============================================

-- Function to create watch progress event
CREATE OR REPLACE FUNCTION create_watch_progress_event()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        INSERT INTO watch_progress_events (user_id, operation, progress_key, content_id, content_type, video_id, season, episode, position, duration, last_watched)
        VALUES (NEW.user_id, TG_OP, NEW.progress_key, NEW.content_id, NEW.content_type, NEW.video_id, NEW.season, NEW.episode, NEW.position, NEW.duration, NEW.last_watched);
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO watch_progress_events (user_id, operation, progress_key, content_id, content_type, video_id, season, episode, position, duration, last_watched)
        VALUES (OLD.user_id, TG_OP, OLD.progress_key, OLD.content_id, OLD.content_type, OLD.video_id, OLD.season, OLD.episode, OLD.position, OLD.duration, OLD.last_watched);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger for watch progress
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'watch_progress_trigger'
          AND tgrelid = 'public.watch_progress'::regclass
    ) THEN
        CREATE TRIGGER watch_progress_trigger
        AFTER INSERT OR UPDATE OR DELETE ON watch_progress
        FOR EACH ROW EXECUTE FUNCTION create_watch_progress_event();
    END IF;
END;
$$;

-- Function to create watched items event
CREATE OR REPLACE FUNCTION create_watched_items_event()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        INSERT INTO watched_items_events (user_id, operation, content_id, content_type, title, season, episode, watched_at)
        VALUES (NEW.user_id, TG_OP, NEW.content_id, NEW.content_type, NEW.title, NEW.season, NEW.episode, NEW.watched_at);
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO watched_items_events (user_id, operation, content_id, content_type, title, season, episode, watched_at)
        VALUES (OLD.user_id, TG_OP, OLD.content_id, OLD.content_type, OLD.title, OLD.season, OLD.episode, OLD.watched_at);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Trigger for watched items
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'watched_items_trigger'
          AND tgrelid = 'public.watched_items'::regclass
    ) THEN
        CREATE TRIGGER watched_items_trigger
        AFTER INSERT OR UPDATE OR DELETE ON watched_items
        FOR EACH ROW EXECUTE FUNCTION create_watched_items_event();
    END IF;
END;
$$;

-- ============================================
-- RPC FUNCTIONS
-- ============================================

-- Generate sync code
CREATE OR REPLACE FUNCTION generate_sync_code()
RETURNS JSON AS $$
DECLARE
    new_code TEXT;
    code_record RECORD;
BEGIN
    -- Generate a 6-character code
    new_code := UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6));

    -- Insert new sync code
    INSERT INTO sync_codes (code, owner_id, expires_at)
    VALUES (new_code, auth.uid(), NOW() + INTERVAL '5 minutes')
    RETURNING * INTO code_record;

    RETURN json_build_object(
        'code', code_record.code,
        'expires_at', code_record.expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Get sync code
CREATE OR REPLACE FUNCTION get_sync_code(p_code TEXT)
RETURNS JSON AS $$
DECLARE
    code_record RECORD;
BEGIN
    SELECT * INTO code_record
    FROM sync_codes
    WHERE code = p_code AND expires_at > NOW();

    IF NOT FOUND THEN
        RETURN json_build_object('success', FALSE, 'message', 'Code not found or expired');
    END IF;

    RETURN json_build_object(
        'success', TRUE,
        'owner_id', code_record.owner_id,
        'claimed', code_record.claimed_at IS NOT NULL
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Claim sync code
CREATE OR REPLACE FUNCTION claim_sync_code(p_code TEXT)
RETURNS JSON AS $$
DECLARE
    code_record RECORD;
    result_owner_id UUID;
BEGIN
    -- Try to claim the code
    UPDATE sync_codes
    SET device_user_id = auth.uid(), claimed_at = NOW()
    WHERE code = p_code AND expires_at > NOW() AND claimed_at IS NULL
    RETURNING owner_id INTO result_owner_id;

    IF NOT FOUND THEN
        RETURN json_build_object('success', FALSE, 'message', 'Code already claimed or expired');
    END IF;

    -- Create linked device record
    INSERT INTO linked_devices (owner_id, device_user_id)
    VALUES (result_owner_id, auth.uid())
    ON CONFLICT DO NOTHING;

    RETURN json_build_object('success', TRUE, 'result_owner_id', result_owner_id, 'message', 'Code claimed successfully');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Unlink device
CREATE OR REPLACE FUNCTION unlink_device(p_device_user_id UUID)
RETURNS VOID AS $$
BEGIN
    DELETE FROM linked_devices
    WHERE owner_id = auth.uid() AND device_user_id = p_device_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Get sync owner
CREATE OR REPLACE FUNCTION get_sync_owner()
RETURNS UUID AS $$
DECLARE
    owner_uuid UUID;
BEGIN
    SELECT owner_id INTO owner_uuid
    FROM linked_devices
    WHERE device_user_id = auth.uid()
    LIMIT 1;

    RETURN COALESCE(owner_uuid, auth.uid());
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Get sync overview
CREATE OR REPLACE FUNCTION get_sync_overview()
RETURNS JSON AS $$
DECLARE
    device_count INTEGER;
    linked_devices_json JSON;
BEGIN
    SELECT COUNT(*) INTO device_count
    FROM linked_devices
    WHERE owner_id = auth.uid();

    SELECT json_agg(json_build_object(
        'device_user_id', device_user_id,
        'device_name', device_name,
        'linked_at', linked_at
    )) INTO linked_devices_json
    FROM linked_devices
    WHERE owner_id = auth.uid();

    RETURN json_build_object(
        'device_count', device_count,
        'linked_devices', COALESCE(linked_devices_json, '[]'::json)
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Get avatar catalog
CREATE OR REPLACE FUNCTION get_avatar_catalog()
RETURNS SETOF avatar_catalog AS $$
BEGIN
    RETURN QUERY SELECT * FROM avatar_catalog ORDER BY sort_order, display_name;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Profile PIN functions
CREATE OR REPLACE FUNCTION set_profile_pin(p_profile_index INTEGER, p_pin TEXT)
RETURNS VOID AS $$
DECLARE
    pin_hash_value TEXT;
BEGIN
    -- Hash the PIN (simple hash for demo, use bcrypt in production)
    pin_hash_value := MD5(p_pin);

    UPDATE profiles
    SET pin_hash = pin_hash_value, pin_enabled = TRUE, pin_attempts = 0, pin_locked_until = NULL
    WHERE user_id = auth.uid() AND profile_index = p_profile_index;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION clear_profile_pin(p_profile_index INTEGER)
RETURNS VOID AS $$
BEGIN
    UPDATE profiles
    SET pin_hash = NULL, pin_enabled = FALSE, pin_attempts = 0, pin_locked_until = NULL
    WHERE user_id = auth.uid() AND profile_index = p_profile_index;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION verify_profile_pin(p_profile_index INTEGER, p_pin TEXT)
RETURNS JSON AS $$
DECLARE
    profile_record RECORD;
    pin_hash_value TEXT;
    max_attempts INTEGER := 5;
    lockout_minutes INTEGER := 15;
BEGIN
    SELECT * INTO profile_record
    FROM profiles
    WHERE user_id = auth.uid() AND profile_index = p_profile_index;

    IF NOT FOUND THEN
        RETURN json_build_object('unlocked', FALSE, 'retry_after_seconds', 0);
    END IF;

    -- Check if locked
    IF profile_record.pin_locked_until IS NOT NULL AND profile_record.pin_locked_until > NOW() THEN
        RETURN json_build_object(
            'unlocked', FALSE,
            'retry_after_seconds', EXTRACT(EPOCH FROM (profile_record.pin_locked_until - NOW()))::INTEGER
        );
    END IF;

    -- Verify PIN
    pin_hash_value := MD5(p_pin);

    IF profile_record.pin_hash = pin_hash_value THEN
        UPDATE profiles SET pin_attempts = 0, pin_locked_until = NULL
        WHERE user_id = auth.uid() AND profile_index = p_profile_index;

        RETURN json_build_object('unlocked', TRUE, 'retry_after_seconds', 0);
    ELSE
        -- Increment attempts
        UPDATE profiles SET pin_attempts = pin_attempts + 1
        WHERE user_id = auth.uid() AND profile_index = p_profile_index;

        -- Lock if too many attempts
        IF profile_record.pin_attempts + 1 >= max_attempts THEN
            UPDATE profiles SET pin_locked_until = NOW() + (lockout_minutes || ' minutes')::INTERVAL
            WHERE user_id = auth.uid() AND profile_index = p_profile_index;

            RETURN json_build_object('unlocked', FALSE, 'retry_after_seconds', lockout_minutes * 60);
        END IF;

        RETURN json_build_object('unlocked', FALSE, 'retry_after_seconds', 0);
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Pull profile locks
CREATE OR REPLACE FUNCTION sync_pull_profile_locks()
RETURNS SETOF profiles AS $$
BEGIN
    RETURN QUERY
    SELECT id, user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins, avatar_id, avatar_url, pin_hash, pin_enabled, pin_locked_until, pin_attempts, created_at, updated_at
    FROM profiles
    WHERE user_id = auth.uid();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for addons
CREATE OR REPLACE FUNCTION sync_push_addons(p_profile_id INTEGER, p_addons JSONB)
RETURNS VOID AS $$
BEGIN
    -- Delete existing addons for this profile
    DELETE FROM addons WHERE user_id = auth.uid() AND profile_id = p_profile_id;

    -- Insert new addons
    INSERT INTO addons (user_id, profile_id, url, name, enabled, sort_order)
    SELECT auth.uid(), p_profile_id, elem->>'url', elem->>'name', COALESCE((elem->>'enabled')::BOOLEAN, TRUE), COALESCE((elem->>'sort_order')::INTEGER, 0)
    FROM jsonb_array_elements(p_addons) AS elem;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_addons(p_profile_id INTEGER)
RETURNS SETOF addons AS $$
BEGIN
    RETURN QUERY SELECT * FROM addons WHERE user_id = auth.uid() AND profile_id = p_profile_id ORDER BY sort_order;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for plugins
CREATE OR REPLACE FUNCTION sync_push_plugins(p_profile_id INTEGER, p_plugins JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM plugins WHERE user_id = auth.uid() AND profile_id = p_profile_id;

    INSERT INTO plugins (user_id, profile_id, url, name, enabled, sort_order, repo_type)
    SELECT auth.uid(), p_profile_id, elem->>'url', elem->>'name', COALESCE((elem->>'enabled')::BOOLEAN, TRUE), COALESCE((elem->>'sort_order')::INTEGER, 0), elem->>'repo_type'
    FROM jsonb_array_elements(p_plugins) AS elem;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_plugins(p_profile_id INTEGER)
RETURNS SETOF plugins AS $$
BEGIN
    RETURN QUERY SELECT * FROM plugins WHERE user_id = auth.uid() AND profile_id = p_profile_id ORDER BY sort_order;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for library
CREATE OR REPLACE FUNCTION sync_push_library(p_profile_id INTEGER, p_items JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM library WHERE user_id = auth.uid() AND profile_id = p_profile_id;

    INSERT INTO library (user_id, profile_id, content_id, content_type, name, poster, poster_shape, background, description, release_info, imdb_rating, genres, addon_base_url, added_at)
    SELECT auth.uid(), p_profile_id, elem->>'content_id', elem->>'content_type', COALESCE(elem->>'name', ''), elem->>'poster', COALESCE(elem->>'poster_shape', 'POSTER'), elem->>'background', elem->>'description', elem->>'release_info', (elem->>'imdb_rating')::REAL, COALESCE(elem->'genres', '[]'::jsonb), elem->>'addon_base_url', COALESCE((elem->>'added_at')::BIGINT, 0)
    FROM jsonb_array_elements(p_items) AS elem;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_library(p_profile_id INTEGER)
RETURNS SETOF library AS $$
BEGIN
    RETURN QUERY SELECT * FROM library WHERE user_id = auth.uid() AND profile_id = p_profile_id ORDER BY added_at DESC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for watch progress
CREATE OR REPLACE FUNCTION sync_push_watch_progress(p_profile_id INTEGER, p_progress_key TEXT, p_content_id TEXT, p_content_type TEXT, p_video_id TEXT, p_season INTEGER, p_episode INTEGER, p_position BIGINT, p_duration BIGINT, p_last_watched BIGINT)
RETURNS VOID AS $$
BEGIN
    INSERT INTO watch_progress (user_id, profile_id, progress_key, content_id, content_type, video_id, season, episode, position, duration, last_watched)
    VALUES (auth.uid(), p_profile_id, p_progress_key, p_content_id, p_content_type, p_video_id, p_season, p_episode, p_position, p_duration, p_last_watched)
    ON CONFLICT (user_id, profile_id, progress_key) DO UPDATE
    SET content_id = EXCLUDED.content_id, content_type = EXCLUDED.content_type, video_id = EXCLUDED.video_id, season = EXCLUDED.season, episode = EXCLUDED.episode, position = EXCLUDED.position, duration = EXCLUDED.duration, last_watched = EXCLUDED.last_watched, updated_at = NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_watch_progress(p_profile_id INTEGER)
RETURNS SETOF watch_progress AS $$
BEGIN
    RETURN QUERY SELECT * FROM watch_progress WHERE user_id = auth.uid() AND profile_id = p_profile_id ORDER BY last_watched DESC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_delete_watch_progress(p_profile_id INTEGER, p_progress_keys JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM watch_progress
    WHERE user_id = auth.uid() AND profile_id = p_profile_id AND progress_key IN (SELECT jsonb_array_elements_text(p_progress_keys));
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Delta sync for watch progress
CREATE OR REPLACE FUNCTION sync_get_watch_progress_delta_cursor(p_profile_id INTEGER)
RETURNS BIGINT AS $$
DECLARE
    cursor_value BIGINT;
BEGIN
    SELECT cursor_value INTO cursor_value
    FROM sync_cursors
    WHERE user_id = auth.uid() AND cursor_type = 'watch_progress_' || p_profile_id;

    RETURN COALESCE(cursor_value, 0);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_watch_progress_delta(p_profile_id INTEGER, p_since_event_id BIGINT)
RETURNS SETOF watch_progress_events AS $$
BEGIN
    -- Update cursor
    INSERT INTO sync_cursors (user_id, cursor_type, cursor_value)
    VALUES (auth.uid(), 'watch_progress_' || p_profile_id, p_since_event_id)
    ON CONFLICT (user_id, cursor_type) DO UPDATE SET cursor_value = EXCLUDED.cursor_value, updated_at = NOW();

    RETURN QUERY
    SELECT * FROM watch_progress_events
    WHERE user_id = auth.uid() AND event_id > p_since_event_id
    ORDER BY event_id ASC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for watched items
CREATE OR REPLACE FUNCTION sync_push_watched_items(p_profile_id INTEGER, p_items JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM watched_items WHERE user_id = auth.uid() AND profile_id = p_profile_id;

    INSERT INTO watched_items (user_id, profile_id, content_id, content_type, title, season, episode, watched_at)
    SELECT auth.uid(), p_profile_id, elem->>'content_id', elem->>'content_type', COALESCE(elem->>'title', ''), (elem->>'season')::INTEGER, (elem->>'episode')::INTEGER, COALESCE((elem->>'watched_at')::BIGINT, 0)
    FROM jsonb_array_elements(p_items) AS elem;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_watched_items(p_profile_id INTEGER)
RETURNS SETOF watched_items AS $$
BEGIN
    RETURN QUERY SELECT * FROM watched_items WHERE user_id = auth.uid() AND profile_id = p_profile_id ORDER BY watched_at DESC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_delete_watched_items(p_profile_id INTEGER, p_content_ids JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM watched_items
    WHERE user_id = auth.uid() AND profile_id = p_profile_id AND content_id IN (SELECT jsonb_array_elements_text(p_content_ids));
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Delta sync for watched items
CREATE OR REPLACE FUNCTION sync_get_watched_items_delta_cursor(p_profile_id INTEGER)
RETURNS BIGINT AS $$
DECLARE
    cursor_value BIGINT;
BEGIN
    SELECT cursor_value INTO cursor_value
    FROM sync_cursors
    WHERE user_id = auth.uid() AND cursor_type = 'watched_items_' || p_profile_id;

    RETURN COALESCE(cursor_value, 0);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_watched_items_delta(p_profile_id INTEGER, p_since_event_id BIGINT)
RETURNS SETOF watched_items_events AS $$
BEGIN
    INSERT INTO sync_cursors (user_id, cursor_type, cursor_value)
    VALUES (auth.uid(), 'watched_items_' || p_profile_id, p_since_event_id)
    ON CONFLICT (user_id, cursor_type) DO UPDATE SET cursor_value = EXCLUDED.cursor_value, updated_at = NOW();

    RETURN QUERY
    SELECT * FROM watched_items_events
    WHERE user_id = auth.uid() AND event_id > p_since_event_id
    ORDER BY event_id ASC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for collections
CREATE OR REPLACE FUNCTION sync_push_collections(p_profile_id INTEGER, p_collections_json JSONB)
RETURNS VOID AS $$
BEGIN
    INSERT INTO collections (user_id, profile_id, collections_json)
    VALUES (auth.uid(), p_profile_id, p_collections_json)
    ON CONFLICT (user_id, profile_id) DO UPDATE SET collections_json = EXCLUDED.collections_json, updated_at = NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_collections(p_profile_id INTEGER)
RETURNS SETOF collections AS $$
BEGIN
    RETURN QUERY SELECT * FROM collections WHERE user_id = auth.uid() AND profile_id = p_profile_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for profile settings
CREATE OR REPLACE FUNCTION sync_push_profile_settings_blob(p_profile_id INTEGER, p_settings_json JSONB)
RETURNS VOID AS $$
BEGIN
    INSERT INTO profile_settings (user_id, profile_id, settings_json)
    VALUES (auth.uid(), p_profile_id, p_settings_json)
    ON CONFLICT (user_id, profile_id) DO UPDATE SET settings_json = EXCLUDED.settings_json, updated_at = NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_profile_settings_blob(p_profile_id INTEGER)
RETURNS SETOF profile_settings AS $$
BEGIN
    RETURN QUERY SELECT * FROM profile_settings WHERE user_id = auth.uid() AND profile_id = p_profile_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for home catalog settings
CREATE OR REPLACE FUNCTION sync_push_home_catalog_settings(p_profile_id INTEGER, p_settings_json JSONB)
RETURNS VOID AS $$
BEGIN
    INSERT INTO home_catalog_settings (user_id, profile_id, settings_json)
    VALUES (auth.uid(), p_profile_id, p_settings_json)
    ON CONFLICT (user_id, profile_id) DO UPDATE SET settings_json = EXCLUDED.settings_json, updated_at = NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_home_catalog_settings(p_profile_id INTEGER)
RETURNS SETOF home_catalog_settings AS $$
BEGIN
    RETURN QUERY SELECT * FROM home_catalog_settings WHERE user_id = auth.uid() AND profile_id = p_profile_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for provider credentials
CREATE OR REPLACE FUNCTION sync_push_provider_credentials(p_provider TEXT, p_credential_json JSONB)
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RAISE EXCEPTION 'legacy provider credential writes are disabled; use the protected ciphertext path'
        USING ERRCODE = '2F005';
END;
$$;

CREATE OR REPLACE FUNCTION sync_pull_provider_credentials()
RETURNS SETOF provider_credentials
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    RETURN QUERY
    SELECT id, user_id, provider, NULL::JSONB, updated_at
      FROM provider_credentials
     WHERE user_id = auth.uid();
END;
$$;

CREATE OR REPLACE FUNCTION sync_delete_provider_credentials(p_provider TEXT)
RETURNS VOID AS $$
BEGIN
    DELETE FROM provider_credentials WHERE user_id = auth.uid() AND provider = p_provider;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Sync functions for profiles
CREATE OR REPLACE FUNCTION sync_push_profiles(p_profiles JSONB)
RETURNS VOID AS $$
BEGIN
    DELETE FROM profiles WHERE user_id = auth.uid();

    INSERT INTO profiles (user_id, profile_index, name, avatar_color_hex, uses_primary_addons, uses_primary_plugins, avatar_id, avatar_url)
    SELECT auth.uid(), (elem->>'profile_index')::INTEGER, COALESCE(elem->>'name', ''), COALESCE(elem->>'avatar_color_hex', '#1E88E5'), COALESCE((elem->>'uses_primary_addons')::BOOLEAN, FALSE), COALESCE((elem->>'uses_primary_plugins')::BOOLEAN, FALSE), elem->>'avatar_id', elem->>'avatar_url'
    FROM jsonb_array_elements(p_profiles) AS elem;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_pull_profiles()
RETURNS SETOF profiles AS $$
BEGIN
    RETURN QUERY SELECT * FROM profiles WHERE user_id = auth.uid() ORDER BY profile_index;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION sync_delete_profile_data(p_profile_id INTEGER)
RETURNS VOID AS $$
BEGIN
    DELETE FROM addons WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM plugins WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM library WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM watch_progress WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM watched_items WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM collections WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM profile_settings WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM home_catalog_settings WHERE user_id = auth.uid() AND profile_id = p_profile_id;
    DELETE FROM profiles WHERE user_id = auth.uid() AND profile_index = p_profile_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- SEED DATA
-- ============================================

-- Insert some default avatars
INSERT INTO avatar_catalog (id, display_name, storage_path, category, sort_order, bg_color) VALUES
('avatar_1', 'Blue', 'avatars/blue.png', 'colors', 1, '#1E88E5'),
('avatar_2', 'Red', 'avatars/red.png', 'colors', 2, '#E53935'),
('avatar_3', 'Green', 'avatars/green.png', 'colors', 3, '#43A047'),
('avatar_4', 'Purple', 'avatars/purple.png', 'colors', 4, '#8E24AA'),
('avatar_5', 'Orange', 'avatars/orange.png', 'colors', 5, '#FB8C00'),
('avatar_6', 'Pink', 'avatars/pink.png', 'colors', 6, '#D81B60')
ON CONFLICT (id) DO NOTHING;

-- ============================================
-- CLEANUP FUNCTIONS
-- ============================================

-- Function to clean up expired sync codes
CREATE OR REPLACE FUNCTION cleanup_expired_sync_codes()
RETURNS VOID AS $$
BEGIN
    DELETE FROM sync_codes WHERE expires_at < NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Schedule cleanup (run daily via pg_cron or external scheduler)
-- SELECT cron.schedule('cleanup-sync-codes', '0 0 * * *', 'SELECT cleanup_expired_sync_codes()');

-- ============================================
-- VERSIONED SYNC CONTRACT MIGRATION (v1)
-- ============================================
-- This block is safe to run after the bootstrap schema and can be re-run.
-- Existing tables and policies remain in place; only additive metadata and
-- guarded coordination objects are introduced here.

-- Client timestamps are the ordering source for replicated records. Existing
-- rows receive zero and are older than any newly-created client mutation.
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE addons ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plugins ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE library ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE watch_progress ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE watched_items ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE collections ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE profile_settings ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE home_catalog_settings ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE provider_credentials ADD COLUMN IF NOT EXISTS client_changed_at BIGINT NOT NULL DEFAULT 0;

-- Stable domain identities are explicit even when the bootstrap schema already
-- supplies an equivalent UNIQUE constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_profiles_identity
    ON profiles(user_id, profile_index);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_addons_identity
    ON addons(user_id, profile_id, url);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_plugins_identity
    ON plugins(user_id, profile_id, url);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_library_identity
    ON library(user_id, profile_id, content_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_watch_progress_identity
    ON watch_progress(user_id, profile_id, progress_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_watched_items_identity
    ON watched_items(user_id, profile_id, content_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_collections_identity
    ON collections(user_id, profile_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_profile_settings_identity
    ON profile_settings(user_id, profile_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_home_catalog_settings_identity
    ON home_catalog_settings(user_id, profile_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sync_provider_credentials_identity
    ON provider_credentials(user_id, provider);

CREATE INDEX IF NOT EXISTS idx_profiles_user_changed_at
    ON profiles(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_addons_user_changed_at
    ON addons(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_plugins_user_changed_at
    ON plugins(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_library_user_changed_at
    ON library(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_watch_progress_user_changed_at
    ON watch_progress(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_watched_items_user_changed_at
    ON watched_items(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_collections_user_changed_at
    ON collections(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_profile_settings_user_changed_at
    ON profile_settings(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_home_catalog_settings_user_changed_at
    ON home_catalog_settings(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_provider_credentials_user_changed_at
    ON provider_credentials(user_id, client_changed_at);

-- Provider credentials are ciphertext-only on the protected sync path. The
-- legacy credential_json column is retained for schema compatibility but is
-- not part of the client contract and must not receive credential values from clients.
ALTER TABLE provider_credentials
    ADD COLUMN IF NOT EXISTS credential_ciphertext TEXT;
ALTER TABLE provider_credentials
    ADD COLUMN IF NOT EXISTS ciphertext_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE provider_credentials
    ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE provider_credentials
    ADD COLUMN IF NOT EXISTS mutation_id TEXT;
CREATE INDEX IF NOT EXISTS idx_provider_credentials_ciphertext_version
    ON provider_credentials(user_id, provider, ciphertext_version);

-- Cursor metadata supports both event-id and client-timestamp based pulls.
ALTER TABLE sync_cursors ADD COLUMN IF NOT EXISTS domain TEXT;
ALTER TABLE sync_cursors ADD COLUMN IF NOT EXISTS profile_id INTEGER;
ALTER TABLE sync_cursors ADD COLUMN IF NOT EXISTS last_event_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_cursors ADD COLUMN IF NOT EXISTS last_client_changed_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sync_cursors ADD COLUMN IF NOT EXISTS schema_version INTEGER NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_sync_cursors_event_reads
    ON sync_cursors(user_id, cursor_type, last_event_id);
CREATE INDEX IF NOT EXISTS idx_sync_cursors_changed_reads
    ON sync_cursors(user_id, domain, profile_id, last_client_changed_at);

-- Event reads remain account-scoped and ordered by their monotonic event ids.
CREATE INDEX IF NOT EXISTS idx_watch_progress_events_user_event
    ON watch_progress_events(user_id, event_id);
CREATE INDEX IF NOT EXISTS idx_watched_items_events_user_event
    ON watched_items_events(user_id, event_id);

-- Generic operation ledger. DELETE rows are tombstones; UPSERT rows retain the
-- envelope payload until the domain-specific writer consumes the operation.
CREATE TABLE IF NOT EXISTS sync_tombstones (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    domain TEXT NOT NULL CHECK (domain IN (
        'PROFILES', 'ADDONS', 'PLUGINS', 'LIBRARY', 'WATCH_PROGRESS',
        'WATCHED_ITEMS', 'COLLECTIONS', 'PROFILE_SETTINGS',
        'HOME_CATALOG_SETTINGS', 'PROVIDER_CREDENTIALS'
    )),
    profile_id INTEGER,
    record_key TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    client_changed_at BIGINT NOT NULL,
    mutation_id TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload_json JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, mutation_id)
);

CREATE INDEX IF NOT EXISTS idx_sync_tombstones_user_changed_at
    ON sync_tombstones(user_id, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_sync_tombstones_identity_reads
    ON sync_tombstones(user_id, domain, profile_id, record_key, client_changed_at);
CREATE INDEX IF NOT EXISTS idx_sync_tombstones_event_reads
    ON sync_tombstones(user_id, created_at, mutation_id);

ALTER TABLE sync_tombstones ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE sync_tombstones FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE sync_tombstones TO authenticated;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'sync_tombstones'
          AND policyname = 'Users can view own sync tombstones'
    ) THEN
        CREATE POLICY "Users can view own sync tombstones"
            ON sync_tombstones FOR SELECT USING (auth.uid() = user_id);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'sync_tombstones'
          AND policyname = 'Users can insert own sync tombstones'
    ) THEN
        CREATE POLICY "Users can insert own sync tombstones"
            ON sync_tombstones FOR INSERT WITH CHECK (FALSE);
    ELSE
        ALTER POLICY "Users can insert own sync tombstones"
            ON sync_tombstones WITH CHECK (FALSE);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'sync_tombstones'
          AND policyname = 'Users can update own sync tombstones'
    ) THEN
        CREATE POLICY "Users can update own sync tombstones"
            ON sync_tombstones FOR UPDATE USING (FALSE) WITH CHECK (FALSE);
    ELSE
        ALTER POLICY "Users can update own sync tombstones"
            ON sync_tombstones USING (FALSE) WITH CHECK (FALSE);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'sync_tombstones'
          AND policyname = 'Users can delete own sync tombstones'
    ) THEN
        CREATE POLICY "Users can delete own sync tombstones"
            ON sync_tombstones FOR DELETE USING (FALSE);
    ELSE
        ALTER POLICY "Users can delete own sync tombstones"
            ON sync_tombstones USING (FALSE);
    END IF;
END;
$$;

-- Harden the pre-existing sync-code insert policy without dropping or
-- renaming it. Anonymous callers cannot create private rows.
ALTER POLICY "Anyone can create sync codes" ON sync_codes
    WITH CHECK (auth.uid() = owner_id);

-- Canonical mutation IDs must match the Android contract exactly. Each of the
-- six components is prefixed by its UTF-8 byte length; prefixes are then
-- concatenated with no separator before SHA-256 hashing.
CREATE OR REPLACE FUNCTION sync_canonical_mutation_id(
    p_owner_user_id UUID,
    p_domain TEXT,
    p_profile_id INTEGER,
    p_record_key TEXT,
    p_client_changed_at BIGINT,
    p_operation TEXT
)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
SET search_path = public, pg_temp
AS $$
    SELECT encode(
        extensions.digest(
            convert_to(
                length(convert_to(p_owner_user_id::TEXT, 'UTF8'))::TEXT || ':' || p_owner_user_id::TEXT ||
                length(convert_to(p_domain, 'UTF8'))::TEXT || ':' || p_domain ||
                length(convert_to(COALESCE(p_profile_id::TEXT, ''), 'UTF8'))::TEXT || ':' || COALESCE(p_profile_id::TEXT, '') ||
                length(convert_to(p_record_key, 'UTF8'))::TEXT || ':' || p_record_key ||
                length(convert_to(p_client_changed_at::TEXT, 'UTF8'))::TEXT || ':' || p_client_changed_at::TEXT ||
                length(convert_to(p_operation, 'UTF8'))::TEXT || ':' || p_operation,
                'UTF8'
            ),
            'sha256'
        ),
        'hex'
    );
$$;

REVOKE EXECUTE ON FUNCTION sync_canonical_mutation_id(UUID, TEXT, INTEGER, TEXT, BIGINT, TEXT)
    FROM PUBLIC, anon, authenticated;

-- Authenticated, security-definer gate for future domain writers. It records
-- the accepted envelope atomically and rejects older identities before the
-- domain-specific table writer runs. No user id or privileged database key is
-- accepted from the client.
CREATE OR REPLACE FUNCTION apply_sync_mutation(
    p_domain TEXT,
    p_profile_id INTEGER,
    p_record_key TEXT,
    p_operation TEXT,
    p_client_changed_at BIGINT,
    p_mutation_id TEXT,
    p_schema_version INTEGER,
    p_payload_json JSONB DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    latest_record RECORD;
    inserted_count INTEGER;
    expected_mutation_id TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'authenticated user context required' USING ERRCODE = '42501';
    END IF;

    IF p_schema_version <> 1 THEN
        RAISE EXCEPTION 'unsupported sync schema version: %', p_schema_version
            USING ERRCODE = '22023';
    END IF;

    IF p_domain NOT IN (
        'PROFILES', 'ADDONS', 'PLUGINS', 'LIBRARY', 'WATCH_PROGRESS',
        'WATCHED_ITEMS', 'COLLECTIONS', 'PROFILE_SETTINGS',
        'HOME_CATALOG_SETTINGS', 'PROVIDER_CREDENTIALS'
    ) THEN
        RAISE EXCEPTION 'unsupported sync domain: %', p_domain USING ERRCODE = '22023';
    END IF;

    IF p_operation NOT IN ('UPSERT', 'DELETE') THEN
        RAISE EXCEPTION 'unsupported sync operation: %', p_operation USING ERRCODE = '22023';
    END IF;
    IF p_client_changed_at IS NULL THEN
        RAISE EXCEPTION 'client timestamp is required' USING ERRCODE = '22023';
    END IF;
    IF p_mutation_id IS NULL OR btrim(p_mutation_id) = '' THEN
        RAISE EXCEPTION 'mutation id is required' USING ERRCODE = '22023';
    END IF;
    IF p_record_key IS NULL OR btrim(p_record_key) = '' THEN
        RAISE EXCEPTION 'record key is required' USING ERRCODE = '22023';
    END IF;
    expected_mutation_id := sync_canonical_mutation_id(
        auth.uid(), p_domain, p_profile_id, p_record_key,
        p_client_changed_at, p_operation
    );
    IF p_mutation_id <> expected_mutation_id THEN
        RAISE EXCEPTION 'mutation id does not match its canonical identity'
            USING ERRCODE = '22023';
    END IF;
    IF p_domain = 'PROVIDER_CREDENTIALS'
       AND (p_operation = 'UPSERT' OR p_payload_json IS NOT NULL) THEN
        RAISE EXCEPTION 'provider credential changes must use the protected ciphertext path'
            USING ERRCODE = '22023';
    END IF;

    -- Serialize competing writes for one account/domain/profile/key identity
    -- before reading the latest accepted operation.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        auth.uid()::TEXT || '|' || p_domain || '|' || COALESCE(p_profile_id::TEXT, '') || '|' || p_record_key,
        0
    ));

    SELECT client_changed_at, mutation_id
      INTO latest_record
      FROM sync_tombstones
     WHERE user_id = auth.uid()
       AND domain = p_domain
       AND profile_id IS NOT DISTINCT FROM p_profile_id
       AND record_key = p_record_key
     ORDER BY client_changed_at DESC, created_at DESC, mutation_id DESC
     LIMIT 1
     FOR UPDATE;

    IF FOUND AND (
        p_client_changed_at < latest_record.client_changed_at
        OR (
            p_client_changed_at = latest_record.client_changed_at
            AND p_mutation_id <> latest_record.mutation_id
        )
    ) THEN
        RETURN jsonb_build_object(
            'accepted', FALSE,
            'reason', 'stale_or_conflicting',
            'current_client_changed_at', latest_record.client_changed_at,
            'current_mutation_id', latest_record.mutation_id
        );
    END IF;

    -- Watch progress mutations must update the materialized table that normal
    -- pulls read. The ledger is the conflict/idempotency layer, while this
    -- branch is the domain writer that keeps watch_progress and its delta
    -- trigger in sync with the accepted mutation.
    IF p_domain = 'WATCH_PROGRESS' THEN
        IF p_operation = 'UPSERT' THEN
            IF p_payload_json IS NULL THEN
                RAISE EXCEPTION 'watch progress upsert payload is required'
                    USING ERRCODE = '22023';
            END IF;
            INSERT INTO watch_progress (
                user_id, profile_id, progress_key, content_id, content_type,
                video_id, season, episode, position, duration, last_watched
            ) VALUES (
                auth.uid(),
                COALESCE((p_payload_json->>'profile_id')::INTEGER, p_profile_id),
                p_record_key,
                p_payload_json->>'content_id',
                p_payload_json->>'content_type',
                COALESCE(p_payload_json->>'video_id', ''),
                (p_payload_json->>'season')::INTEGER,
                (p_payload_json->>'episode')::INTEGER,
                COALESCE((p_payload_json->>'position')::BIGINT, 0),
                COALESCE((p_payload_json->>'duration')::BIGINT, 0),
                COALESCE((p_payload_json->>'last_watched')::BIGINT, p_client_changed_at)
            )
            ON CONFLICT (user_id, profile_id, progress_key) DO UPDATE SET
                content_id = EXCLUDED.content_id,
                content_type = EXCLUDED.content_type,
                video_id = EXCLUDED.video_id,
                season = EXCLUDED.season,
                episode = EXCLUDED.episode,
                position = EXCLUDED.position,
                duration = EXCLUDED.duration,
                last_watched = EXCLUDED.last_watched,
                updated_at = NOW()
            WHERE watch_progress.last_watched <= EXCLUDED.last_watched;
        ELSE
            DELETE FROM watch_progress
             WHERE user_id = auth.uid()
               AND profile_id = p_profile_id
               AND progress_key = p_record_key;
        END IF;
    END IF;

    -- Library / watched_items must also materialize into the tables that pulls read.
    -- Tombstones alone are not enough for cross-device restore.
    IF p_domain = 'LIBRARY' THEN
        IF p_operation = 'UPSERT' THEN
            IF p_payload_json IS NULL THEN
                RAISE EXCEPTION 'library upsert payload is required'
                    USING ERRCODE = '22023';
            END IF;
            INSERT INTO library (
                user_id, profile_id, content_id, content_type, name, poster,
                poster_shape, background, description, release_info, imdb_rating,
                genres, addon_base_url, added_at
            ) VALUES (
                auth.uid(),
                COALESCE((p_payload_json->>'profile_id')::INTEGER, p_profile_id),
                COALESCE(p_payload_json->>'content_id', p_record_key),
                COALESCE(p_payload_json->>'content_type', 'movie'),
                COALESCE(p_payload_json->>'name', ''),
                p_payload_json->>'poster',
                COALESCE(p_payload_json->>'poster_shape', 'POSTER'),
                p_payload_json->>'background',
                p_payload_json->>'description',
                p_payload_json->>'release_info',
                (p_payload_json->>'imdb_rating')::REAL,
                COALESCE(p_payload_json->'genres', '[]'::jsonb),
                p_payload_json->>'addon_base_url',
                COALESCE((p_payload_json->>'added_at')::BIGINT, p_client_changed_at, 0)
            )
            ON CONFLICT (user_id, profile_id, content_id) DO UPDATE SET
                content_type = EXCLUDED.content_type,
                name = EXCLUDED.name,
                poster = EXCLUDED.poster,
                poster_shape = EXCLUDED.poster_shape,
                background = EXCLUDED.background,
                description = EXCLUDED.description,
                release_info = EXCLUDED.release_info,
                imdb_rating = EXCLUDED.imdb_rating,
                genres = EXCLUDED.genres,
                addon_base_url = EXCLUDED.addon_base_url,
                added_at = EXCLUDED.added_at,
                updated_at = NOW();
        ELSE
            DELETE FROM library
             WHERE user_id = auth.uid()
               AND profile_id = p_profile_id
               AND content_id = p_record_key;
        END IF;
    END IF;

    IF p_domain = 'WATCHED_ITEMS' THEN
        IF p_operation = 'UPSERT' THEN
            IF p_payload_json IS NULL THEN
                RAISE EXCEPTION 'watched item upsert payload is required'
                    USING ERRCODE = '22023';
            END IF;
            INSERT INTO watched_items (
                user_id, profile_id, content_id, content_type, title,
                season, episode, watched_at
            ) VALUES (
                auth.uid(),
                COALESCE((p_payload_json->>'profile_id')::INTEGER, p_profile_id),
                COALESCE(p_payload_json->>'content_id', p_record_key),
                COALESCE(p_payload_json->>'content_type', 'movie'),
                COALESCE(p_payload_json->>'title', ''),
                (p_payload_json->>'season')::INTEGER,
                (p_payload_json->>'episode')::INTEGER,
                COALESCE((p_payload_json->>'watched_at')::BIGINT, p_client_changed_at, 0)
            )
            ON CONFLICT (user_id, profile_id, content_id) DO UPDATE SET
                content_type = EXCLUDED.content_type,
                title = EXCLUDED.title,
                season = EXCLUDED.season,
                episode = EXCLUDED.episode,
                watched_at = EXCLUDED.watched_at,
                updated_at = NOW()
            WHERE watched_items.watched_at <= EXCLUDED.watched_at;
        ELSE
            DELETE FROM watched_items
             WHERE user_id = auth.uid()
               AND profile_id = p_profile_id
               AND content_id = p_record_key;
        END IF;
    END IF;

    INSERT INTO sync_tombstones (
        user_id, domain, profile_id, record_key, operation,
        client_changed_at, mutation_id, schema_version, payload_json
    ) VALUES (
        auth.uid(), p_domain, p_profile_id, p_record_key, p_operation,
        p_client_changed_at, p_mutation_id, p_schema_version, p_payload_json
    )
    ON CONFLICT (user_id, mutation_id) DO NOTHING;

    GET DIAGNOSTICS inserted_count = ROW_COUNT;
    RETURN jsonb_build_object(
        'accepted', TRUE,
        'duplicate', inserted_count = 0,
        'mutation_id', p_mutation_id
    );
END;
$$;

REVOKE EXECUTE ON FUNCTION apply_sync_mutation(TEXT, INTEGER, TEXT, TEXT, BIGINT, TEXT, INTEGER, JSONB)
    FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION apply_sync_mutation(TEXT, INTEGER, TEXT, TEXT, BIGINT, TEXT, INTEGER, JSONB)
    TO authenticated;

-- ============================================
-- PROTECTED PROVIDER CREDENTIAL PATH (v1)
-- ============================================
-- Keep the legacy function signatures for deployed callers, but make the
-- legacy JSON path unusable. Provider integrations must use the protected
-- ciphertext writer below and must not expose credential_json to clients.
REVOKE ALL ON TABLE provider_credentials FROM PUBLIC, anon, authenticated;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can view own provider credentials') THEN
        ALTER POLICY "Users can view own provider credentials"
            ON provider_credentials USING (FALSE);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can insert own provider credentials') THEN
        ALTER POLICY "Users can insert own provider credentials"
            ON provider_credentials WITH CHECK (FALSE);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can update own provider credentials') THEN
        ALTER POLICY "Users can update own provider credentials"
            ON provider_credentials USING (FALSE) WITH CHECK (FALSE);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'provider_credentials' AND policyname = 'Users can delete own provider credentials') THEN
        ALTER POLICY "Users can delete own provider credentials"
            ON provider_credentials USING (FALSE);
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION sync_push_provider_credentials(
    p_provider TEXT,
    p_credential_json JSONB
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public
AS $$
BEGIN
    RAISE EXCEPTION 'legacy provider credential writes are disabled; use the protected ciphertext path'
        USING ERRCODE = '2F005';
END;
$$;

CREATE OR REPLACE FUNCTION sync_pull_provider_credentials()
RETURNS SETOF provider_credentials
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN QUERY
    SELECT *
      FROM provider_credentials
     WHERE user_id = auth.uid();
END;
$$;

-- Keep the old five-argument signature unusable so a pre-v1 caller cannot
-- bypass the mutation guard by resolving the legacy overload.
CREATE OR REPLACE FUNCTION sync_push_provider_credential_ciphertext(
    p_provider TEXT,
    p_credential_ciphertext TEXT,
    p_ciphertext_version INTEGER,
    p_client_changed_at BIGINT,
    p_schema_version INTEGER
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = public, pg_temp
AS $$
BEGIN
    RAISE EXCEPTION 'provider credential writes require a canonical mutation id'
        USING ERRCODE = '2F005';
END;
$$;

-- The protected server path uses the same identity lock and timestamp/mutation
-- guard as apply_sync_mutation. Ciphertext is never copied into the ledger.
CREATE OR REPLACE FUNCTION sync_push_provider_credential_ciphertext(
    p_provider TEXT,
    p_credential_ciphertext TEXT,
    p_ciphertext_version INTEGER,
    p_client_changed_at BIGINT,
    p_schema_version INTEGER,
    p_mutation_id TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    latest_ledger RECORD;
    current_provider RECORD;
    expected_mutation_id TEXT;
    inserted_count INTEGER;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'authenticated user context required' USING ERRCODE = '42501';
    END IF;
    IF p_schema_version <> 1 THEN
        RAISE EXCEPTION 'unsupported sync schema version: %', p_schema_version
            USING ERRCODE = '22023';
    END IF;
    IF p_provider IS NULL OR btrim(p_provider) = '' THEN
        RAISE EXCEPTION 'provider is required' USING ERRCODE = '22023';
    END IF;
    IF p_credential_ciphertext IS NULL OR btrim(p_credential_ciphertext) = '' THEN
        RAISE EXCEPTION 'credential ciphertext is required' USING ERRCODE = '22023';
    END IF;
    IF p_ciphertext_version < 1 THEN
        RAISE EXCEPTION 'ciphertext version must be positive' USING ERRCODE = '22023';
    END IF;
    IF p_client_changed_at IS NULL THEN
        RAISE EXCEPTION 'client timestamp is required' USING ERRCODE = '22023';
    END IF;
    IF p_mutation_id IS NULL OR btrim(p_mutation_id) = '' THEN
        RAISE EXCEPTION 'mutation id is required' USING ERRCODE = '22023';
    END IF;

    expected_mutation_id := sync_canonical_mutation_id(
        auth.uid(), 'PROVIDER_CREDENTIALS', NULL, p_provider,
        p_client_changed_at, 'UPSERT'
    );
    IF p_mutation_id <> expected_mutation_id THEN
        RAISE EXCEPTION 'mutation id does not match its canonical identity'
            USING ERRCODE = '22023';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        auth.uid()::TEXT || '|PROVIDER_CREDENTIALS||' || p_provider,
        0
    ));

    SELECT client_changed_at, mutation_id
      INTO latest_ledger
      FROM sync_tombstones
     WHERE user_id = auth.uid()
       AND domain = 'PROVIDER_CREDENTIALS'
       AND profile_id IS NULL
       AND record_key = p_provider
     ORDER BY client_changed_at DESC, created_at DESC, mutation_id DESC
     LIMIT 1
     FOR UPDATE;

    IF FOUND AND (
        p_client_changed_at < latest_ledger.client_changed_at
        OR (
            p_client_changed_at = latest_ledger.client_changed_at
            AND p_mutation_id IS DISTINCT FROM latest_ledger.mutation_id
        )
    ) THEN
        RETURN jsonb_build_object(
            'accepted', FALSE,
            'reason', 'stale_or_conflicting',
            'current_client_changed_at', latest_ledger.client_changed_at,
            'current_mutation_id', latest_ledger.mutation_id
        );
    END IF;

    SELECT client_changed_at, mutation_id
      INTO current_provider
      FROM provider_credentials
     WHERE user_id = auth.uid()
       AND provider = p_provider
     FOR UPDATE;

    IF FOUND AND (
        p_client_changed_at < current_provider.client_changed_at
        OR (
            p_client_changed_at = current_provider.client_changed_at
            AND p_mutation_id IS DISTINCT FROM current_provider.mutation_id
        )
    ) THEN
        RETURN jsonb_build_object(
            'accepted', FALSE,
            'reason', 'stale_or_conflicting',
            'current_client_changed_at', current_provider.client_changed_at,
            'current_mutation_id', current_provider.mutation_id
        );
    END IF;

    IF FOUND
       AND p_client_changed_at = current_provider.client_changed_at
       AND p_mutation_id = current_provider.mutation_id THEN
        RETURN jsonb_build_object(
            'accepted', TRUE,
            'duplicate', TRUE,
            'mutation_id', p_mutation_id
        );
    END IF;

    INSERT INTO sync_tombstones (
        user_id, domain, profile_id, record_key, operation,
        client_changed_at, mutation_id, schema_version, payload_json
    ) VALUES (
        auth.uid(), 'PROVIDER_CREDENTIALS', NULL, p_provider, 'UPSERT',
        p_client_changed_at, p_mutation_id, p_schema_version, NULL
    )
    ON CONFLICT (user_id, mutation_id) DO NOTHING;

    GET DIAGNOSTICS inserted_count = ROW_COUNT;

    INSERT INTO provider_credentials (
        user_id, provider, credential_ciphertext, ciphertext_version,
        client_changed_at, schema_version, mutation_id, credential_json
    ) VALUES (
        auth.uid(), p_provider, p_credential_ciphertext, p_ciphertext_version,
        p_client_changed_at, p_schema_version, p_mutation_id, '{}'::JSONB
    )
    ON CONFLICT (user_id, provider) DO UPDATE SET
        credential_ciphertext = EXCLUDED.credential_ciphertext,
        ciphertext_version = EXCLUDED.ciphertext_version,
        client_changed_at = EXCLUDED.client_changed_at,
        schema_version = EXCLUDED.schema_version,
        mutation_id = EXCLUDED.mutation_id,
        updated_at = NOW();

    RETURN jsonb_build_object(
        'accepted', TRUE,
        'duplicate', inserted_count = 0,
        'mutation_id', p_mutation_id
    );
END;
$$;

-- Direct REST clients cannot execute the legacy, delete, pull, or protected
-- provider RPCs. A trusted server endpoint invokes the protected functions
-- with authenticated context; no privileged key is shipped to Android.
REVOKE ALL ON FUNCTION sync_push_provider_credentials(TEXT, JSONB) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION sync_pull_provider_credentials() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION sync_delete_provider_credentials(TEXT) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION sync_push_provider_credential_ciphertext(TEXT, TEXT, INTEGER, BIGINT, INTEGER)
    FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION sync_push_provider_credential_ciphertext(TEXT, TEXT, INTEGER, BIGINT, INTEGER, TEXT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION sync_pull_provider_credentials() TO service_role;
GRANT EXECUTE ON FUNCTION sync_push_provider_credential_ciphertext(TEXT, TEXT, INTEGER, BIGINT, INTEGER, TEXT)
    TO service_role;
REVOKE SELECT (credential_json, credential_ciphertext)
    ON provider_credentials FROM PUBLIC, anon, authenticated;

-- Edge Functions use these service-role-only wrappers after validating the
-- caller's access token. The owner id is accepted only on this server path;
-- Android never receives the service-role key. The request claim is scoped to
-- the current transaction so the existing auth.uid()-based guards remain the
-- single source of truth for ownership and mutation identity.
CREATE OR REPLACE FUNCTION sync_push_provider_credential_ciphertext_for_server(
    p_owner_user_id UUID,
    p_provider TEXT,
    p_credential_ciphertext TEXT,
    p_ciphertext_version INTEGER,
    p_client_changed_at BIGINT,
    p_schema_version INTEGER,
    p_mutation_id TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_owner_user_id IS NULL THEN
        RAISE EXCEPTION 'owner user id is required' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('request.jwt.claim.sub', p_owner_user_id::TEXT, TRUE);
    RETURN sync_push_provider_credential_ciphertext(
        p_provider,
        p_credential_ciphertext,
        p_ciphertext_version,
        p_client_changed_at,
        p_schema_version,
        p_mutation_id
    );
END;
$$;

CREATE OR REPLACE FUNCTION sync_delete_provider_credentials_for_server(
    p_owner_user_id UUID,
    p_provider TEXT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_owner_user_id IS NULL THEN
        RAISE EXCEPTION 'owner user id is required' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('request.jwt.claim.sub', p_owner_user_id::TEXT, TRUE);
    PERFORM sync_delete_provider_credentials(p_provider);
END;
$$;

REVOKE ALL ON FUNCTION sync_push_provider_credential_ciphertext_for_server(UUID, TEXT, TEXT, INTEGER, BIGINT, INTEGER, TEXT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION sync_push_provider_credential_ciphertext_for_server(UUID, TEXT, TEXT, INTEGER, BIGINT, INTEGER, TEXT)
    TO service_role;
REVOKE ALL ON FUNCTION sync_delete_provider_credentials_for_server(UUID, TEXT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION sync_delete_provider_credentials_for_server(UUID, TEXT)
    TO service_role;

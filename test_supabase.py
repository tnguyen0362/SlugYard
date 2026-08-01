#!/usr/bin/env python3
"""
Test script for SlugYard Supabase integration
Run this after applying supabase-schema.sql to your Supabase project
"""

import requests
import json
import sys
from pathlib import Path

# Load credentials from local.properties
def load_credentials():
    props_file = Path(__file__).parent / "local.properties"
    if not props_file.exists():
        print("[FAIL] local.properties not found!")
        sys.exit(1)

    props = {}
    with open(props_file) as f:
        for line in f:
            if '=' in line and not line.startswith('#'):
                key, value = line.strip().split('=', 1)
                props[key] = value

    return (
        props.get('SLUGYARD_SUPABASE_URL'),
        props.get('SLUGYARD_SUPABASE_ANON_KEY'),
    )

SUPABASE_URL, ANON_KEY = load_credentials()

if not SUPABASE_URL or not ANON_KEY:
    print("[FAIL] Missing Supabase credentials in local.properties")
    sys.exit(1)

print(f"[INFO] Testing Supabase connection...")
print(f"   URL: {SUPABASE_URL}")
print("   Key: configured (value withheld)")
print()

# Headers for all requests
headers = {
    'apikey': ANON_KEY,
    'Authorization': f'Bearer {ANON_KEY}',
    'Content-Type': 'application/json'
}

def test_connection():
    """Test basic connection to Supabase"""
    print("1. Testing connection...")
    try:
        response = requests.get(f"{SUPABASE_URL}/rest/v1/", headers=headers, timeout=10)
        if response.status_code == 200:
            print("   [OK] Connection successful!")
            return True
        else:
            print(f"   [FAIL] Connection failed: {response.status_code}")
            print(f"   Response: {response.text[:200]}")
            return False
    except Exception as e:
        print(f"   [FAIL] Connection error: {e}")
        return False

def test_table_exists(table_name):
    """Check if a table exists"""
    try:
        response = requests.get(
            f"{SUPABASE_URL}/rest/v1/{table_name}?select=count",
            headers=headers,
            timeout=10
        )
        return response.status_code == 200
    except:
        return False

def test_tables():
    """Test if all required tables exist"""
    print("\n2. Testing tables...")
    required_tables = [
        'profiles', 'addons', 'plugins', 'library',
        'watch_progress', 'watched_items', 'collections',
        'profile_settings', 'home_catalog_settings',
        'provider_credentials', 'linked_devices',
        'sync_codes', 'avatar_catalog', 'sync_cursors',
        'sync_tombstones', 'watch_progress_events', 'watched_items_events'
    ]

    all_exist = True
    for table in required_tables:
        exists = test_table_exists(table)
        status = "[OK]" if exists else "[FAIL]"
        print(f"   {status} {table}")
        if not exists:
            all_exist = False

    return all_exist

def test_avatar_catalog():
    """Test if avatar catalog has seed data"""
    print("\n3. Testing avatar catalog...")
    try:
        response = requests.get(
            f"{SUPABASE_URL}/rest/v1/avatar_catalog?select=id,display_name",
            headers=headers,
            timeout=10
        )
        if response.status_code == 200:
            avatars = response.json()
            print(f"   [OK] Found {len(avatars)} avatars")
            for avatar in avatars[:3]:
                print(f"      - {avatar['display_name']}")
            return True
        else:
            print(f"   [FAIL] Failed to fetch avatars: {response.status_code}")
            return False
    except Exception as e:
        print(f"   [FAIL] Error: {e}")
        return False

def test_rpc_functions():
    """Test if RPC functions are callable"""
    print("\n4. Testing RPC functions...")

    # Test get_avatar_catalog RPC
    try:
        response = requests.post(
            f"{SUPABASE_URL}/rest/v1/rpc/get_avatar_catalog",
            headers=headers,
            json={},
            timeout=10
        )
        if response.status_code == 200:
            print("   [OK] get_avatar_catalog() works")
            return True
        else:
            print(f"   [FAIL] get_avatar_catalog() failed: {response.status_code}")
            return False
    except Exception as e:
        print(f"   [FAIL] Error: {e}")
        return False

def main():
    print("=" * 60)
    print("SlugYard Supabase Integration Test")
    print("=" * 60)
    print()

    results = {
        'connection': test_connection(),
        'tables': test_tables() if True else False,
        'avatars': test_avatar_catalog(),
        'rpc': test_rpc_functions()
    }

    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)

    for test_name, passed in results.items():
        status = "[PASS]" if passed else "[FAIL]"
        print(f"{status} - {test_name}")

    all_passed = all(results.values())

    if all_passed:
        print("\n[SUCCESS] All tests passed! Your Supabase setup is ready.")
        print("\nNext steps:")
        print("1. Create an account in the app")
        print("2. Add an addon")
        print("3. Check if it syncs to Supabase")
        return 0
    else:
        print("\n[WARNING] Some tests failed. Please check:")
        print("1. Did you run supabase-schema.sql in the SQL Editor?")
        print("2. Are the credentials correct in local.properties?")
        print("3. Is your Supabase project active?")
        return 1

if __name__ == "__main__":
    sys.exit(main())

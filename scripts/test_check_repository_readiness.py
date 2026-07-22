import unittest
from pathlib import Path

from scripts.check_repository_readiness import check_file, check_path, check_text


class ReadinessCheckerTest(unittest.TestCase):
    def test_product_copy_rejects_nuvio(self):
        self.assertEqual(check_text("Welcome to NuvioTV"), ["product-facing Nuvio reference"])

    def test_notice_is_an_allowed_attribution_file(self):
        self.assertEqual(check_file(Path("NOTICE"), "Original project: NuvioTV"), [])

    def test_legacy_storage_key_is_an_allowed_compatibility_file(self):
        self.assertEqual(
            check_file(Path("app/src/main/java/com/sluggyard/tv/data/local/PlayerSettingsDataStore.kt"), '"nuvio_performance_mode_enabled"'),
            [],
        )

    def test_keystore_path_is_rejected(self):
        self.assertIn("secret path", check_path(Path("sluggyard.jks")))


if __name__ == "__main__":
    unittest.main()

import unittest

from scripts.release_beta import normalize_subject


class ReleaseBetaCopyTest(unittest.TestCase):
    def test_release_copy_does_not_insert_legacy_product_name(self):
        rendered = normalize_subject("SlugYard UI update")
        self.assertEqual(rendered, "SlugYard UI update")
        self.assertNotIn("Nuvio", rendered)


if __name__ == "__main__":
    unittest.main()

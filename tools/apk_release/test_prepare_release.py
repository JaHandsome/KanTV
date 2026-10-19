import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).with_name("prepare_release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PublicBuildBoundaryTests(unittest.TestCase):
    def test_pairing_is_removed_even_when_invoking_shell_is_paired(self):
        signing = {key: "synthetic-signing-input" for key in release.SIGNING}
        environment = dict(signing, PATH="tool-path", KANTV_HUB_ENDPOINT="private-pairing",
                           KANTV_UPDATE_TOKEN="private-token", KANTV_BUILD_GIT_REVISION="old")
        result = release.public_environment(environment, "reviewed-revision", 123)
        self.assertNotIn("KANTV_HUB_ENDPOINT", result)
        self.assertNotIn("KANTV_UPDATE_TOKEN", result)
        self.assertEqual(result["KANTV_BUILD_GIT_REVISION"], "reviewed-revision")
        self.assertEqual(result["KANTV_BUILD_EPOCH_MS"], "123")
        self.assertEqual(result["PATH"], "tool-path")
        self.assertTrue(all(result[key] == signing[key] for key in signing))

    def test_missing_signing_fails_before_build(self):
        with self.assertRaisesRegex(ValueError, "Missing signing environment"):
            release.public_environment({}, "revision", 0)

    def test_generated_config_must_explicitly_disable_diagnostics_and_pairing(self):
        safe = "\n".join(f'public static final String {name} = "";' for name in release.EMPTY_DEFAULTS)
        safe += "\npublic static final boolean KANTV_DIAGNOSTICS = false;"
        release.check_build_config(safe)
        for text in (safe.replace('HUB_DEFAULT_ENDPOINT = ""', 'HUB_DEFAULT_ENDPOINT = "paired"'),
                     safe.replace("KANTV_DIAGNOSTICS = false", "KANTV_DIAGNOSTICS = true"),
                     safe.replace('UPDATE_DEFAULT_TOKEN = ""', 'MISSING_TOKEN = ""')):
            with self.assertRaises(ValueError):
                release.check_build_config(text)


if __name__ == "__main__":
    unittest.main()

"""Offline safety regressions for the opt-in Docker acceptance runner."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_docker_private_sources as runner
from docker_private_sources_fixture import make_ts_video
from app.verifier import ts_judge


class DockerPrivateSourceGuardTests(unittest.TestCase):
    def test_remote_endpoints_are_rejected_without_echo(self):
        for endpoint in ('tcp://private-host:2375', 'ssh://private-host',
                         'unix://remote/var/run/docker.sock', ' http://private-host'):
            with self.subTest(endpoint=endpoint):
                with self.assertRaises(RuntimeError) as caught:
                    runner.local_endpoint({'DOCKER_HOST': endpoint}, mock.Mock())
                self.assertNotIn(endpoint, str(caught.exception))

    def test_context_precedes_host_and_current_context_is_tool_resolved(self):
        inspect = mock.Mock(return_value='unix:///tmp/synthetic-docker.sock')
        self.assertEqual('unix:///tmp/synthetic-docker.sock', runner.local_endpoint(
            {'DOCKER_CONTEXT': 'chosen', 'DOCKER_HOST': 'tcp://must-not-use'}, inspect))
        inspect.assert_called_once_with('chosen')
        inspect.reset_mock()
        runner.local_endpoint({}, inspect)
        inspect.assert_called_once_with(None)
        inspect.reset_mock()
        runner.local_endpoint({'DOCKER_HOST': 'unix:///tmp/host.sock'}, inspect)
        inspect.assert_not_called()

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'existing'
            path.mkdir()
            marker = path / 'marker'
            marker.write_text('preserve bytes')
            with self.assertRaises(FileExistsError):
                runner.create_output_dir(path)
            self.assertEqual('preserve bytes', marker.read_text())
            self.assertEqual([marker], list(path.iterdir()))

    @staticmethod
    def tracked_repository(root):
        subprocess.run(['git', 'init', '-q', str(root)], check=True)
        for name in runner.PUBLIC_FILES | {'server/app/main.py'}:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('public input\n')
        subprocess.run(['git', '-C', str(root), 'add', 'server'], check=True)

    def test_export_uses_current_tracked_public_inputs_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'repo'
            self.tracked_repository(root)
            private = root / 'server/config/settings.json'
            private.write_text('private sentinel')
            (root / 'server/app/untracked.py').write_text('untracked sentinel')
            (root / 'server/app/main.py').write_text('current tracked public edit')
            destination = Path(temporary) / 'export'
            manifest = runner.export_public_inputs(root, destination)
            self.assertEqual(runner.PUBLIC_FILES | {'server/app/main.py'}, set(manifest))
            self.assertFalse((destination / 'server/config/settings.json').exists())
            self.assertFalse((destination / 'server/app/untracked.py').exists())
            self.assertEqual('current tracked public edit',
                             (destination / 'server/app/main.py').read_text())

    def test_selected_file_and_parent_symlinks_are_not_read_or_exported(self):
        for parent_link in (False, True):
            with self.subTest(parent_link=parent_link), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / 'repo'
                self.tracked_repository(root)
                private_dir = root / 'server/runtime/private'
                private_dir.mkdir(parents=True)
                sentinel = private_dir / 'main.py'
                sentinel.write_text('private sentinel')
                main = root / 'server/app/main.py'
                if parent_link:
                    shutil.rmtree(main.parent)
                    main.parent.symlink_to(private_dir, target_is_directory=True)
                else:
                    main.unlink()
                    main.symlink_to(sentinel)
                original = Path.read_bytes
                def guarded_read(path):
                    self.assertNotEqual(sentinel.resolve(), path.resolve(), 'private sentinel was read')
                    return original(path)
                destination = Path(temporary) / 'export'
                with mock.patch.object(Path, 'read_bytes', guarded_read):
                    with self.assertRaisesRegex(RuntimeError, 'symlink'):
                        runner.export_public_inputs(root, destination)
                self.assertFalse((destination / 'server/app/main.py').exists())

    def test_exception_and_cleanup_failure_cannot_report_pass(self):
        for fail_phase, fail_cleanup in ((True, False), (False, True)):
            with self.subTest(fail_cleanup=fail_cleanup), tempfile.TemporaryDirectory() as temporary:
                output = runner.create_output_dir(Path(temporary) / 'evidence')
                acceptance = runner.Acceptance(output, Path(temporary), 'python:3.11-slim')
                acceptance.prepare = mock.Mock()
                acceptance.scenario = mock.Mock(side_effect=RuntimeError('synthetic phase failure') if fail_phase else None)
                acceptance.cleanup = mock.Mock(side_effect=RuntimeError('synthetic cleanup failure') if fail_cleanup else None)
                with redirect_stdout(io.StringIO()):
                    code = acceptance.execute()
                acceptance.cleanup.assert_called_once_with()
                self.assertEqual(1, code)
                self.assertEqual('failed', json.loads((output / 'RESULT.json').read_text())['status'])
                self.assertFalse((output / 'COMPLETE').exists())

    def test_failure_stdout_keeps_failed_command_log_after_successful_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = runner.create_output_dir(Path(temporary) / 'evidence')
            acceptance = runner.Acceptance(output, Path(temporary), 'python:3.11-slim')
            (output / 'failure.log').write_text('synthetic build failure evidence\n')
            acceptance.result['commands'] = [{'label': 'build', 'log': 'failure.log',
                'expected_exit_codes': [0], 'exit_code': 1}]
            acceptance.prepare = mock.Mock(side_effect=RuntimeError('failed build'))
            acceptance.cleanup = mock.Mock()
            captured = io.StringIO()
            with redirect_stdout(captured):
                self.assertEqual(1, acceptance.execute())
            self.assertIn('synthetic build failure evidence', captured.getvalue())

    def test_docker_control_secrets_not_inherited_and_base_image_is_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.dict(os.environ, {'DOCKER_CONTEXT': 'private-context',
                                             'DOCKER_CUSTOM_HEADERS': 'secret-sentinel',
                                             'DOCKER_TLS_VERIFY': '1'}):
                acceptance = runner.Acceptance(Path(temporary), Path(temporary), 'python:3.11-slim')
            self.assertNotIn('DOCKER_CONTEXT', acceptance.env)
            self.assertNotIn('DOCKER_CUSTOM_HEADERS', acceptance.env)
            self.assertNotIn('DOCKER_TLS_VERIFY', acceptance.env)
            with mock.patch.object(runner, 'Acceptance') as factory:
                factory.return_value.execute.return_value = 0
                self.assertEqual(0, runner.main(['--output-dir', str(Path(temporary) / 'new'),
                    '--base-image', 'public.ecr.aws/docker/library/python:3.11-slim']))
                self.assertEqual('public.ecr.aws/docker/library/python:3.11-slim', factory.call_args.args[2])

    def test_media_fixture_contains_recognized_video(self):
        self.assertEqual('video(TS:H.264视频)', ts_judge(make_ts_video()))


if __name__ == '__main__':
    unittest.main()

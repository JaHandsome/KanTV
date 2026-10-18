#!/usr/bin/env python3
"""Exercise public Compose and private-source policy on an isolated local Docker daemon.

Only tracked public build inputs enter the new workspace. Runtime configuration,
media, data and evidence are synthetic. No existing deployment is read or edited.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import quote, urlsplit
import urllib.request
import uuid

NETWORK_ENV = ('http_proxy', 'https_proxy', 'all_proxy', 'no_proxy',
               'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY',
               'SSL_CERT_FILE', 'SSL_CERT_DIR')
PUBLIC_FILES = frozenset(('server/Dockerfile', 'server/.dockerignore',
                          'server/docker-compose.yml', 'server/tools/init_config.py',
                          'server/tools/renormalize_channels.py',
                          'server/config/settings.example.json'))
BUILD_TIMEOUT = 180
TEST_TIMEOUT = 180


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def create_output_dir(requested=None):
    if requested is None:
        return Path(tempfile.mkdtemp(prefix='kantv-private-sources-')).resolve()
    output = Path(requested).absolute()
    output.mkdir(parents=True, exist_ok=False)
    return output.resolve()


def local_endpoint(environment, inspect_context):
    """Docker's explicit context takes precedence over host and current context."""
    if environment.get('DOCKER_CONTEXT'):
        endpoint = inspect_context(environment['DOCKER_CONTEXT'])
    elif environment.get('DOCKER_HOST'):
        endpoint = environment['DOCKER_HOST']
    else:
        endpoint = inspect_context(None)
    parts = urlsplit(endpoint)
    require(endpoint.startswith('unix:///') and not parts.netloc
            and not parts.query and not parts.fragment and '%' not in endpoint
            and not any(char.isspace() or ord(char) < 32 for char in endpoint)
            and Path(parts.path).is_absolute(), 'Docker endpoint must be a local Unix socket')
    return endpoint


def public_input_bytes(repository, name):
    source = repository / name
    components = [repository.joinpath(*Path(name).parts[:index])
                  for index in range(1, len(Path(name).parts) + 1)]
    require(not any(path.is_symlink() for path in components) and source.is_file(),
            'Public input must be a regular file without symlink components')
    require(source.resolve().is_relative_to(repository.resolve()), 'Public input escaped the checkout')
    return source.read_bytes()


def export_public_inputs(repository, destination):
    """Explicit allowlist, intersected with Git tracking; never walk runtime/config."""
    result = subprocess.run(['git', '-C', str(repository), 'ls-files', '-z'],
                            capture_output=True, timeout=15, check=False)
    require(result.returncode == 0, 'A Git checkout is required')
    names = [name for name in result.stdout.decode().split('\0') if name]
    selected = sorted(name for name in names if name in PUBLIC_FILES
                      or re.fullmatch(r'server/app/[^/]+\.py', name))
    require(PUBLIC_FILES.issubset(selected), 'Required public Docker inputs are not tracked')
    manifest = {}
    for name in selected:
        data = public_input_bytes(repository, name)
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        manifest[name] = hashlib.sha256(data).hexdigest()
    return manifest


def _metadata(command, environment):
    """Read only selected tool metadata, without persisting endpoints or user config."""
    try:
        result = subprocess.run(command, env=environment, capture_output=True,
                                timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise RuntimeError('Docker metadata lookup failed') from None
    require(result.returncode == 0, 'Docker metadata lookup failed')
    return result.stdout.decode().strip()


class Acceptance:
    def __init__(self, output, repository, base_image):
        self.output, self.repository, self.base_image = output, repository, base_image
        self.run_id = uuid.uuid4().hex
        self.project = 'kantv-private-' + self.run_id
        self.image = 'kantv-private-test:' + self.run_id
        self.work = output / 'work'
        self.server = self.work / 'server'
        self.env = {key: os.environ[key] for key in ('PATH', 'HOME') + NETWORK_ENV if key in os.environ}
        self.docker = None
        self.compose = None
        self.command_count = 0
        self.deadline = None
        self.owns_project = self.owns_image = False
        self.result = {'run_id': self.run_id, 'project': self.project, 'image': self.image,
                       'status': 'running', 'stages': {}, 'commands': [], 'cleanup': {}}
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def write_json(self, name, value):
        (self.output / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')

    def command(self, label, args, *, timeout=30, allowed=(0,), extra_env=None, limited=True):
        if limited and self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
        require(timeout > 0, 'Acceptance deadline exceeded')
        self.command_count += 1
        prefix = '%03d-%s' % (self.command_count, label)
        logged_args = list(args)
        if '--host' in logged_args:
            logged_args[logged_args.index('--host') + 1] = '<local-unix-socket>'
        entry = {'label': label, 'command': logged_args, 'started_unix': time.time(),
                 'log': prefix + '.log', 'expected_exit_codes': list(allowed) if allowed is not None else None}
        environment = dict(self.env, **(extra_env or {}))
        process = None
        try:
            with (self.output / entry['log']).open('w') as log:
                process = subprocess.Popen(args, cwd=self.work if self.work.exists() else self.output,
                                           env=environment, stdout=log, stderr=subprocess.STDOUT,
                                           start_new_session=True)
                entry['pid'] = process.pid
                try:
                    entry['exit_code'] = process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    entry['timed_out'] = True
                    raise RuntimeError('Command timed out: ' + label) from None
        finally:
            if process is not None and process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=3)
            entry.setdefault('exit_code', process.returncode if process is not None else None)
            entry['finished_unix'] = time.time()
            self.result['commands'].append(entry)
            self.write_json(prefix + '.json', entry)
        text = (self.output / entry['log']).read_text(errors='replace')
        require(allowed is None or entry['exit_code'] in allowed,
                'Command failed: %s (exit %s)' % (label, entry['exit_code']))
        return text, entry['exit_code']

    def dc(self, label, args, **kwargs):
        return self.command(label, self.compose + args, **kwargs)[0]

    def docker_json(self, label, args):
        return json.loads(self.command(label, self.docker + args)[0])

    def prepare(self):
        self.result['public_inputs'] = export_public_inputs(self.repository, self.work)
        # Only the Docker tool reads its own context metadata. Python never reads user config.
        discovery_env = dict(self.env)
        if 'DOCKER_CONFIG' in os.environ:
            discovery_env['DOCKER_CONFIG'] = os.environ['DOCKER_CONFIG']

        def inspect_context(name):
            command = ['docker', 'context', 'inspect'] + ([name] if name else [])
            return _metadata(command + ['--format', '{{.Endpoints.docker.Host}}'], discovery_env)

        endpoint = local_endpoint(os.environ, inspect_context)
        try:
            is_socket = stat.S_ISSOCK(os.stat(urlsplit(endpoint).path).st_mode)
        except OSError:
            is_socket = False
        require(is_socket, 'Local Docker socket is unavailable')
        # Learn only Compose's plugin directory before switching to an empty Docker config.
        plugins = json.loads(_metadata(['docker', '--host', endpoint, 'info', '--format',
                                        '{{json .ClientInfo.Plugins}}'], discovery_env))
        plugin_dirs = sorted({str(Path(item['Path']).parent) for item in plugins or []
                              if item.get('Name') == 'compose' and item.get('Path')})
        config = self.output / 'docker-config'
        config.mkdir()
        self.write_json('docker-config/config.json', {'auths': {}, 'cliPluginsExtraDirs': plugin_dirs})
        self.env['DOCKER_CONFIG'] = str(config)
        self.env['COMPOSE_PROJECT_NAME'] = self.project
        self.docker = ['docker', '--host', endpoint]
        require(self.command('docker-os', self.docker + ['info', '--format', '{{.OSType}}'])[0].strip()
                == 'linux', 'Linux containers are required')
        self.command('compose-version', self.docker + ['compose', 'version', '--short'])
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.port = listener.getsockname()[1]
        self.env['IPTV_PORT'] = str(self.port)
        self.result['host_binding'] = {'ip': '127.0.0.1', 'port': self.port}
        self.command('initialize', [sys.executable, 'server/tools/init_config.py', '--output',
                                   'server/runtime/config'])
        fixture = self.output / 'fixture.py'
        data = public_input_bytes(self.repository, 'server/tests/docker_private_sources_fixture.py')
        fixture.write_bytes(data)
        self.result['fixture_sha256'] = hashlib.sha256(data).hexdigest()
        label = {'org.kantv.private-source-test': self.run_id}
        common = {'image': self.image, 'entrypoint': ['python3', '/fixture.py'],
                  'volumes': [str(fixture) + ':/fixture.py:ro'], 'labels': label}
        override = {'services': {'iptv-nas': {'image': self.image, 'labels': label},
                                'fixture-a': dict(common, environment={'FIXTURE_PORTS': '8080,8081'}),
                                'fixture-b': dict(common, environment={'FIXTURE_PORTS': '8080'})}}
        self.write_json('compose.override.json', override)
        self.compose = self.docker + ['compose', '-f', str(self.server / 'docker-compose.yml'),
                                     '-f', str(self.output / 'compose.override.json')]
        self.dc('compose-config', ['config', '--quiet'])
        require(not self.command('image-preflight', self.docker + ['image', 'ls', '--filter',
                'reference=' + self.image, '--format', '{{.ID}}'])[0].strip(), 'Unique image already exists')
        self.owns_image = True
        self.dc('build', ['build', '--pull', '--no-cache', '--build-arg',
                         'BASE_IMAGE=' + self.base_image, 'iptv-nas'], timeout=BUILD_TIMEOUT)

    def inspect(self, service):
        cid = self.dc('id-' + service, ['ps', '-q', service]).strip()
        require(bool(cid), 'Container is missing: ' + service)
        return self.docker_json('inspect-' + service, ['inspect', cid])[0]

    def wait(self, label, condition, timeout=45):
        end = min(time.monotonic() + timeout, self.deadline)
        while time.monotonic() < end:
            if condition():
                return
            time.sleep(min(2, max(0, end - time.monotonic())))
        raise RuntimeError('Timed out waiting for ' + label)

    def http_outputs(self, label):
        content = {}
        for path in ('healthz', 'live.txt', 'live.m3u'):
            with self.opener.open('http://127.0.0.1:%d/%s' % (self.port, path), timeout=3) as response:
                require(response.status == 200, 'Host HTTP check failed')
                body = response.read()
            (self.output / (label + '-' + path)).write_bytes(body)
            content[path] = body.decode()
        return content

    def ready(self):
        try:
            with self.opener.open('http://127.0.0.1:%d/healthz' % self.port, timeout=2) as response:
                return response.status == 200
        except OSError:
            return False

    def tick(self):
        self.wait('host health', self.ready)
        def finished():
            logs = self.dc('startup-logs', ['logs', '--no-color', '--tail=250', 'iptv-nas'])
            return '任务 discovery 完成' in logs.rpartition(' 启动, 项目根: /app')[2]
        self.wait('startup discovery', finished)

    def exec_python(self, label, service, code, *args):
        return self.dc(label, ['exec', '-T', service, 'python3', '-c', code] + list(args))

    def stats(self, service):
        code = "import urllib.request; print(urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:8080/stats',timeout=3).read().decode())"
        return json.loads(self.exec_python('stats-' + service, service, code))

    def database(self):
        code = """import sqlite3,json
c=sqlite3.connect('file:/app/data/iptv.db?mode=ro',uri=True); c.row_factory=sqlite3.Row
print(json.dumps({'sources':c.execute('SELECT count(*) FROM sources').fetchone()[0],
'active':c.execute("SELECT count(*) FROM sources WHERE status='active'").fetchone()[0],
'lineups':c.execute('SELECT count(*) FROM lineups').fetchone()[0],
'rows':[dict(r) for r in c.execute('SELECT id,url_key,channel_key,url,status FROM sources ORDER BY id')]}))
"""
        return json.loads(self.exec_python('database', 'iptv-nas', code))

    def fetch(self, url, allowed=False):
        code = """import json,sys
from app.config import Config
from app.net import NetClient,NetPolicyError
try:
    status,body,ct=NetClient(Config('/app')).fetch(sys.argv[1],timeout=2)
except NetPolicyError as exc:
    print(json.dumps({'outcome':'blocked','type':type(exc).__name__}))
    sys.exit(0 if sys.argv[2]=='blocked' else 1)
else:
    print(json.dumps({'outcome':'allowed','status':status,'bytes':len(body)}))
    sys.exit(0 if sys.argv[2]=='allowed' and status==200 else 1)
"""
        return json.loads(self.exec_python('netclient-fetch', 'iptv-nas', code, url,
                                          'allowed' if allowed else 'blocked'))

    def origins(self, entries):
        path = self.server / 'runtime/config/settings.json'
        settings = json.loads(path.read_text())
        settings['network']['private_source_origins'] = entries
        path.write_text(json.dumps(settings, indent=2) + '\n')

    def restart_origins(self, entries):
        self.dc('stop', ['stop', 'iptv-nas'])
        self.origins(entries)
        self.dc('start', ['start', 'iptv-nas'])
        self.tick()

    def record(self, number, detail):
        self.result['stages'][str(number)] = detail
        self.write_json('stage-%s.json' % number, detail)
        print('PASS stage %s: %s' % (number, detail['name']), flush=True)

    @staticmethod
    def identity(state):
        return [{key: row[key] for key in ('id', 'url_key', 'channel_key', 'url')}
                for row in state['rows']]

    def scenario(self):
        self.deadline = time.monotonic() + TEST_TIMEOUT
        project_filter = 'label=com.docker.compose.project=' + self.project
        for resource in ('container', 'network'):
            command = (['ps', '-a'] if resource == 'container' else ['network', 'ls'])
            require(not self.command('project-preflight', self.docker + command +
                    ['--filter', project_filter, '--format', '{{.ID}}'])[0].strip(), 'Unique project already exists')
        self.owns_project = True
        self.dc('up', ['up', '-d', '--no-build', '--pull', 'never'])
        self.tick()
        app = self.inspect('iptv-nas')
        require(app['HostConfig']['PortBindings'] == {'8420/tcp': [
            {'HostIp': '127.0.0.1', 'HostPort': str(self.port)}]}, 'Host bind changed')
        require(app['HostConfig']['RestartPolicy']['Name'] == 'unless-stopped', 'Restart policy changed')
        hc = app['Config']['Healthcheck']
        require((hc['Interval'], hc['Timeout'], hc['StartPeriod'], hc['Retries']) ==
                (60_000_000_000, 10_000_000_000, 120_000_000_000, 3), 'Public healthcheck changed')
        require(not any(item.split('=', 1)[0].lower().endswith('_proxy') for item in app['Config']['Env']),
                'Proxy environment reached application')
        require({m['Destination']: m['Source'] for m in app['Mounts']} == {
                '/app/config': str(self.server / 'runtime/config'),
                '/app/data': str(self.server / 'runtime/data')}, 'Runtime mounts escaped workspace')
        network = next(iter(app['NetworkSettings']['Networks']))
        addresses = {}
        for service in ('fixture-a', 'fixture-b'):
            fixture = self.inspect(service)
            require(not fixture['HostConfig']['PortBindings']
                    and set(fixture['NetworkSettings']['Networks']) == {network}, 'Fixture isolation changed')
            addresses[service] = fixture['NetworkSettings']['Networks'][network]['IPAddress']
        self.wait('Docker healthy', lambda: self.inspect('iptv-nas')['State']['Health']['Status'] == 'healthy', 90)
        settings = json.loads((self.server / 'runtime/config/settings.json').read_text())
        require(settings['network']['private_source_origins'] == []
                and settings['hub'] == {'enabled': False, 'feedback_token': ''}, 'Unsafe initial defaults')
        content = self.http_outputs('default')
        require(not content['live.txt'].strip() and '#EXTINF' not in content['live.m3u'], 'Default output not empty')
        require(self.database()['sources'] == 0 and self.stats('fixture-a') == {}
                and self.stats('fixture-b') == {}, 'Default sources not empty')
        self.record(1, {'name': 'default empty and Docker healthy', 'fixture_addresses': addresses})

        a, b = (addresses[service] for service in ('fixture-a', 'fixture-b'))
        origin_a, origin_b = 'http://%s:8080' % a, 'http://%s:8080' % b
        config = self.server / 'runtime/config'
        (config / 'channels.json').write_text(json.dumps({'channels': ['CCTV1'], 'order': ['CCTV1'],
                                                        'aliases': {'CCTV-1': 'CCTV1'}}))
        (config / 'url_lists.json').write_text(json.dumps({'lists': [{'id': 'synthetic',
            'url': origin_a + '/playlist.m3u', 'enabled': True, 'refresh_minutes': 360}]}))
        self.dc('restart-default-policy', ['restart', 'iptv-nas'])
        self.tick()
        state = self.database()
        content = self.http_outputs('denied')
        require(state['sources'] == state['active'] == 0 and self.stats('fixture-a') == {}
                and self.stats('fixture-b') == {} and not content['live.txt'].strip()
                and '#EXTINF' not in content['live.m3u'], 'Default LAN denial failed')
        self.record(2, {'name': 'configured private source denied by default', 'database': state})

        self.restart_origins([origin_a])
        self.wait('source activation', lambda: self.database()['active'] == 1)
        state = self.database()
        require(state['sources'] == state['active'] == state['lineups'] == 1
                and state['rows'][0]['channel_key'] == 'CCTV1', 'Pipeline activation failed')
        original_identity = self.identity(state)
        content = self.http_outputs('allowed')
        require(all(origin_a + '/master.m3u8' in content[path] for path in ('live.txt', 'live.m3u')),
                'HLS missing from output')
        counts = self.stats('fixture-a')
        require(all(counts.get('8080 ' + path, 0) > 0 for path in
                    ('/playlist.m3u', '/master.m3u8', '/media.m3u8', '/segment.ts')), 'Full HLS pipeline not exercised')
        self.record(3, {'name': 'exact origin permits complete HLS pipeline', 'database': state, 'fixture_a': counts})

        code = "import socket,sys; [socket.create_connection((a,int(p)),timeout=2).close() for a,p in [(sys.argv[1],'8081'),(sys.argv[2],'8080')]]"
        self.exec_python('negative-targets-tcp-reachable', 'iptv-nas', code, a, b)
        denied = [self.fetch(url) for url in (origin_b + '/master.m3u8', 'http://%s:8081/master.m3u8' % a,
                  'https://%s:8080/master.m3u8' % a, 'http://fixture-a:8080/master.m3u8')]
        same = self.fetch(origin_a + '/same_redirect', True)
        self.restart_origins([origin_a, origin_b])
        control = 'http://127.0.0.1:8080/control/redirect?target=' + quote(origin_b + '/master.m3u8', safe='')
        code = "import sys,urllib.request; urllib.request.build_opener(urllib.request.ProxyHandler({})).open(sys.argv[1],timeout=2).read()"
        self.exec_python('set-cross-origin-target', 'fixture-a', code, control)
        cross = self.fetch(origin_a + '/cross_redirect')
        counts = self.stats('fixture-a')
        require(not any(key.startswith('8081 ') for key in counts) and self.stats('fixture-b') == {},
                'Rejected target received an HTTP request')
        self.record(4, {'name': 'origin, DNS and redirect boundaries', 'denied': denied,
                       'same_origin_redirect': same, 'cross_origin_both_authorized': cross,
                       'fixture_a': counts, 'fixture_b': {}, 'negative_targets_tcp_reachable': True})

        self.dc('stop-before-revoke', ['stop', 'iptv-nas'])
        before = [self.stats(service) for service in ('fixture-a', 'fixture-b')]
        self.origins([])
        self.dc('start-revoked', ['start', 'iptv-nas'])
        self.tick()
        self.fetch(origin_a + '/master.m3u8')
        self.fetch(origin_b + '/master.m3u8')
        after = [self.stats(service) for service in ('fixture-a', 'fixture-b')]
        require(before == after, 'Revoked startup or client fetched a source')
        require(self.identity(self.database()) == original_identity, 'Revocation deleted source identity')
        self.record(5, {'name': 'stop/edit/start revokes later fetches', 'before': before, 'after': after,
                       'note': 'Database state, generated lists and client URLs are not automatically removed.'})

        self.dc('down-for-persistence', ['down'])
        self.dc('up-for-persistence', ['up', '-d', '--no-build', '--pull', 'never'])
        self.tick()
        state = self.database()
        require(self.identity(state) == original_identity, 'Persistent source identity changed')
        self.fetch(origin_a + '/master.m3u8')
        self.fetch(origin_b + '/master.m3u8')
        require(self.stats('fixture-a') == {} and self.stats('fixture-b') == {}, 'Revoked source fetched after recreation')
        self.http_outputs('recreated')
        self.record(6, {'name': 'down/up preserves data and revocation', 'database': state,
                       'note': 'Fixture counters reset on recreation. Old source IPs test denial only; source status may change.'})

        invalid = self.work / 'invalid-config'
        self.command('initialize-invalid', [sys.executable, 'server/tools/init_config.py', '--output', str(invalid)])
        path = invalid / 'settings.json'
        settings = json.loads(path.read_text())
        invalid_value = 'http://127.0.0.123:9876'
        settings['network']['private_source_origins'] = [invalid_value]
        path.write_text(json.dumps(settings))
        text, rc = self.command('invalid-start', self.compose + ['run', '--rm', '--no-deps', '--pull', 'never', 'iptv-nas'],
                               extra_env={'IPTV_CONFIG_DIR': str(invalid), 'IPTV_DATA_DIR': str(self.work / 'invalid-data')}, allowed=(1,))
        require('network.private_source_origins[0]: invalid private source origin' in text
                and invalid_value not in text and '127.0.0.123' not in text, 'Invalid config error leaked value or failed incorrectly')
        self.record(7, {'name': 'invalid authorization rejects startup without value echo', 'expected_exit_code': rc})

    def cleanup(self):
        errors = []
        self.deadline = None
        def attempt(label, args):
            try:
                return self.command(label, args, timeout=35)[0]
            except Exception as exc:
                errors.append(str(exc))
                return None
        if self.owns_project:
            attempt('cleanup-logs', self.compose + ['logs', '--no-color', '--tail=100', 'iptv-nas'])
            attempt('cleanup-down', self.compose + ['down'])
            for resource, command in (('containers', ['ps', '-a']), ('networks', ['network', 'ls']),
                                      ('volumes', ['volume', 'ls'])):
                text = attempt('cleanup-' + resource, self.docker + command + ['--filter',
                    'label=com.docker.compose.project=' + self.project, '--format', '{{.ID}}' if resource != 'volumes' else '{{.Name}}'])
                if text is not None and text.strip():
                    errors.append('Project ' + resource + ' remain')
        if self.owns_image:
            text = attempt('cleanup-image-list', self.docker + ['image', 'ls', '--filter',
                'reference=' + self.image, '--format', '{{.ID}}'])
            if text is not None and text.strip():
                attempt('cleanup-image-remove', self.docker + ['image', 'rm', self.image])
            text = attempt('cleanup-image-check', self.docker + ['image', 'ls', '--filter',
                'reference=' + self.image, '--format', '{{.ID}}'])
            if text is not None and text.strip():
                errors.append('Unique image remains')
        self.result['cleanup'] = {'attempted_project': self.owns_project, 'attempted_image': self.owns_image,
                                  'errors': errors, 'success': not errors}
        require(not errors, 'Cleanup did not complete')

    def execute(self):
        saved_handlers = {}
        if threading.current_thread() is threading.main_thread():
            def interrupted(signum, _frame):
                raise InterruptedError('Acceptance interrupted by signal %d' % signum)
            for signum in (signal.SIGINT, signal.SIGTERM):
                saved_handlers[signum] = signal.signal(signum, interrupted)
        started = time.monotonic()
        try:
            self.prepare()
            self.scenario()
            self.result['status'] = 'passed'
        except (Exception, KeyboardInterrupt) as exc:
            self.result.update(status='failed', error={'type': type(exc).__name__, 'message': str(exc)})
        finally:
            for signum in saved_handlers:
                signal.signal(signum, signal.SIG_IGN)
            try:
                self.cleanup()
            except Exception as exc:
                self.result.update(status='failed', cleanup_error=str(exc))
            self.result['elapsed_seconds'] = round(time.monotonic() - started, 3)
            self.write_json('RESULT.json', self.result)
            for signum, handler in saved_handlers.items():
                signal.signal(signum, handler)
        passed = self.result['status'] == 'passed'
        if passed:
            (self.output / 'COMPLETE').write_text('passed including cleanup\n')
        else:
            print('FAIL: ' + self.result.get('error', {}).get('message', self.result.get('cleanup_error', 'unknown')), flush=True)
            failed = [entry for entry in self.result['commands'] if entry.get('timed_out')
                      or (entry['expected_exit_codes'] is not None
                          and entry['exit_code'] not in entry['expected_exit_codes'])]
            non_cleanup = [entry for entry in self.result['commands'] if not entry['label'].startswith('cleanup-')]
            failed_main = [entry for entry in failed if not entry['label'].startswith('cleanup-')]
            failed_cleanup = [entry for entry in failed if entry['label'].startswith('cleanup-')]
            app_logs = [entry for entry in self.result['commands'] if entry['label'] == 'cleanup-logs']
            for entry in ((failed_main[-1:] or non_cleanup[-1:]) + app_logs[-1:] + failed_cleanup[-1:]):
                log = self.output / entry['log']
                if log.exists():
                    print('\n--- %s ---\n%s' % (entry['label'], '\n'.join(log.read_text(errors='replace').splitlines()[-20:])[-5000:]), flush=True)
        print('Result: ' + str(self.output / 'RESULT.json'), flush=True)
        return 0 if passed else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, help='New evidence directory; an existing path is rejected')
    parser.add_argument('--base-image', default='python:3.11-slim', help='Explicit Python base image; no automatic fallback')
    args = parser.parse_args(argv)
    try:
        output = create_output_dir(args.output_dir)
    except OSError:
        parser.exit(1, 'Output directory must be new; nothing was overwritten.\n')
    repository = Path(__file__).resolve().parents[2]
    return Acceptance(output, repository, args.base_image).execute()


if __name__ == '__main__':
    sys.exit(main())

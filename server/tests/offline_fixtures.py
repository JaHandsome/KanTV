# -*- coding: utf-8 -*-
"""Public offline fixtures. Never read local settings, tokens, or runtime data."""
import base64
import copy
import json
import os
import shutil
import tempfile

from app.config import Config, DEFAULT_SETTINGS

SERVER_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBLIC_CONFIG_FILES = ('channels.json', 'repos.json', 'rules.json', 'url_lists.json')

# Deliberately synthetic 16-byte vectors, unrelated to any service or subscription.
KID_HEX = '00112233445566778899aabbccddeeff'
KEY_HEX = 'ffeeddccbbaa99887766554433221100'
KID_UUID = '00112233-4455-6677-8899-aabbccddeeff'
KID_B64 = base64.urlsafe_b64encode(bytes.fromhex(KID_HEX)).decode().rstrip('=')
KEY_B64 = base64.urlsafe_b64encode(bytes.fromhex(KEY_HEX)).decode().rstrip('=')
LICENSE = json.dumps({'keys': [{'kty': 'oct', 'k': KEY_B64, 'kid': KID_B64}],
                      'type': 'temporary'})
DRM_PLAYLIST = ('#EXTM3U\n#EXTINF:-1 tvb-id="J",翡翠台\n'
                '#KODIPROP:inputstream.adaptive.manifest_type=mpd\n'
                '#KODIPROP:inputstream.adaptive.license_type=clearkey\n'
                '#KODIPROP:inputstream.adaptive.license_key=' + LICENSE + '\n'
                'https://drm.example.invalid/live.mpd\n')


def write_test_config(root):
    """Copy only public metadata; generate explicit test settings from defaults."""
    config_dir = os.path.join(root, 'config')
    os.makedirs(config_dir, exist_ok=True)
    for name in PUBLIC_CONFIG_FILES:
        shutil.copyfile(os.path.join(SERVER_ROOT, 'config', name),
                        os.path.join(config_dir, name))
    settings = copy.deepcopy(DEFAULT_SETTINGS)
    settings['http']['snippet_host'] = 'example.invalid'
    settings['client_caps'] = {'version': 'offline-test', 'caps': {'dash_clearkey': True}}
    settings['signed_url_params_by_host'] = {
        'edgeware.tvb.com': ['sig', 'ts', 'c', 'ext_start_limit', 'int_start_limit'],
    }
    with open(os.path.join(config_dir, 'settings.json'), 'w', encoding='utf-8') as handle:
        json.dump(settings, handle)


def make_config():
    """Own a temporary root for tests that need configuration but no disk fixture."""
    temporary_root = tempfile.TemporaryDirectory(prefix='iptv-offline-')
    write_test_config(temporary_root.name)
    config = Config(temporary_root.name)
    config._offline_temporary_root = temporary_root
    return config

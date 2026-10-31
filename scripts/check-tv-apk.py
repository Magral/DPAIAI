#!/usr/bin/env python3
"""Check TV identity, launcher/boot components, JNI payload and the QUIC guard."""
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk = Path(sys.argv[1])
aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0/aapt'
badging = subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
assert "package: name='io.github.magral.dpipilot.tv'" in badging, 'Wrong application ID'
manifest = subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
for value in ['android.intent.category.LEANBACK_LAUNCHER','android.intent.action.BOOT_COMPLETED','TvVpnService','TvEngineService','TvScoutService',':scout','MaintenanceJob','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',':engine']:
    assert value in manifest, 'Missing TV manifest component: '+value
with zipfile.ZipFile(apk) as archive:
    for abi in ['armeabi-v7a','arm64-v8a','x86','x86_64']:
        native = archive.read(f'lib/{abi}/libbyedpi.so')
        assert b'__wrap_sendto' in native and b'__wrap_connect' in native, 'Missing selective QUIC guard: '+abi
        assert archive.getinfo(f'lib/{abi}/libhev-socks5-tunnel.so').file_size > 0
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for cls in [b'Lio/github/magral/dpipilot/tv/TvActivity;',b'Lio/github/dovecoteescapee/byedpi/core/TProxyService;']:
        assert cls in dex, 'Missing TV/JNI entry point'
    for removed in [b'Lio/github/magral/dpipilot/tv/MediaProbe;',b'Lio/github/magral/dpipilot/tv/ProbeHttp;',b'ytInitialPlayerResponse']:
        assert removed not in dex, 'Obsolete synthetic media path remains in APK'
print('TV application ID, launcher, boot receiver, engine process and all four native ABIs verified')

#!/usr/bin/env python3
"""Phone identity, legacy/modern engine separation, JNI and update/target boundaries."""
import os,pathlib,subprocess,sys,zipfile
apk=pathlib.Path(sys.argv[1]);aapt=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/34.0.0/aapt'
badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
assert "package: name='io.github.magral.dpipilot'" in badging
manifest=subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
for item in ['MobileEngineService',':mobileengine','TuningWorkerService',':tuner','CatalogUpdateJob','android.permission.BIND_JOB_SERVICE','com.instagram.android','com.google.android.youtube']:
    assert item in manifest,item
with zipfile.ZipFile(apk) as package:
    for abi in ['armeabi-v7a','arm64-v8a','x86','x86_64']:
        modern=package.read(f'lib/{abi}/libmobilepilot.so');legacy=package.read(f'lib/{abi}/libbyedpi.so')
        assert b'Java_io_github_dovecoteescapee_byedpi_core_MobileProxy_jniCreateSocketWithCommandLine' in modern
        assert b'Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniCreateSocket' in legacy
        assert b'__wrap_connect' in modern and b'__wrap_sendto' in modern
        assert package.getinfo(f'lib/{abi}/libhev-socks5-tunnel.so').file_size>0
    dex=b''.join(package.read(n) for n in package.namelist() if n.endswith('.dex'))
    for removed in [b'ytInitialPlayerResponse',b'TelegramBridgeService',b'MTProxyService']:
        assert removed not in dex
print('Phone identity, four ABIs, isolated modern/legacy JNI, job and Instagram/YouTube boundaries verified')

#!/usr/bin/env python3
"""Build actual Go C ABI and JNI adapter on host; no Cloudflare accounts or network registration."""
import base64, ctypes, json, os, pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as temporary:
    out=pathlib.Path(temporary);lib=out/'libpilotwarp.so'
    subprocess.run(['go','-C',str(root/'warp/native'),'build','-mod=readonly','-buildvcs=false','-buildmode=c-shared','-o',str(lib),'.'],check=True)
    backend=ctypes.CDLL(str(lib))
    backend.PilotWarpKeys.restype=ctypes.c_void_p
    backend.PilotWarpStats.restype=ctypes.c_void_p
    backend.PilotWarpStart.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_char_p,ctypes.c_int,ctypes.c_size_t];backend.PilotWarpStart.restype=ctypes.c_void_p
    libc=ctypes.CDLL(None);libc.free.argtypes=[ctypes.c_void_p]
    def result(pointer):
        try:return ctypes.string_at(pointer).decode()
        finally:libc.free(pointer)
    first=json.loads(result(backend.PilotWarpKeys()));second=json.loads(result(backend.PilotWarpKeys()))
    assert first!=second
    assert len(base64.b64decode(first['private'],validate=True))==32 and len(base64.b64decode(first['public'],validate=True))==32
    original=os.open('/dev/null',os.O_RDONLY);owned=os.dup(original)
    try:
        assert result(backend.PilotWarpStart(owned,b'',b'[0,0,0]',1,0))=='TUN initialization failed'
        try:os.fstat(owned);raise AssertionError('failed start leaked TUN duplicate')
        except OSError:pass
        os.fstat(original)
        backend.PilotWarpStop();backend.PilotWarpStop()
        assert json.loads(result(backend.PilotWarpStats()))=={}
    finally:os.close(original)
    java=pathlib.Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/default-java'))
    headers=pathlib.Path(os.environ.get('DPI_JNI_HEADERS',str(java/'include')))
    if not (headers/'jni.h').exists():raise RuntimeError('JDK development headers required (JAVA_HOME/include/jni.h)')
    shim=out/'libwarpjni.so'
    subprocess.run(['c++','-std=c++17','-shared','-fPIC','-I'+str(headers),'-I'+str(headers/'linux'),str(root/'app/src/main/cpp/warp_jni.cpp'),'-ldl','-o',str(shim)],check=True)
    subprocess.run(['nm','-D',str(shim)],check=True,stdout=subprocess.DEVNULL)
print('WARP native: real source build, fresh keys, failed-start fd ownership, stop/stats and JNI adapter compiled')

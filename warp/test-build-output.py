#!/usr/bin/env python3
"""Actual build script outputs must survive NDK's clean-installed-binaries boundary.
Go compilation is stubbed here; real compilation and APK symbol audit run separately.
"""
import os,pathlib,shutil,subprocess,tempfile
source=pathlib.Path(__file__).resolve().parent
with tempfile.TemporaryDirectory() as temporary:
    root=pathlib.Path(temporary);(root/'warp/native').mkdir(parents=True)
    for name in ['build-android.sh','licenses.py']:shutil.copyfile(source/name,root/'warp'/name)
    tools=root/'tools';tools.mkdir();(tools/'LICENSE').write_text('Go test license')
    go=tools/'go';go.write_text('''#!/usr/bin/env python3
import json,os,pathlib,sys
args=sys.argv[1:]
if 'build' in args:
    file=pathlib.Path(args[args.index('-o')+1]);file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(b'WARP-OUTPUT')
elif 'list' in args: print(json.dumps({'Main':True}))
elif 'env' in args and 'GOROOT' in args:print(os.environ['TEST_GOROOT'])
else:raise SystemExit('unexpected Go invocation')
''');go.chmod(0o755)
    env=dict(os.environ,PATH=str(tools)+os.pathsep+os.environ['PATH'],ANDROID_NDK_HOME=str(root/'ndk'),TEST_GOROOT=str(tools))
    subprocess.run(['bash',str(root/'warp/build-android.sh')],env=env,check=True,stdout=subprocess.DEVNULL)
    outputs=list(root.rglob('libpilotwarp.so'));assert len(outputs)==4
    # Exact NDK r26b clean-installed-binaries target boundary: NDK_LIBS_OUT/<ABI>/*.
    for abi in ['armeabi-v7a','arm64-v8a','x86','x86_64']:
        directory=root/'app/src/main/jniLibs'/abi
        if directory.exists():
            for file in directory.iterdir():file.unlink()
    assert all(file.exists() for file in outputs),'NDK clean removed the source-built WARP libraries'
print('WARP build output survives NDK cleanup for all four ABIs')

"""Every command emitted by the production Kotlin generator must launch the pinned engine."""
import json,os,pathlib,sys,tempfile
root=pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0,str(root/'tv/strategies'))
from build_catalog import compile_engine,starts
from refresh_catalog import safe
commands=json.loads((pathlib.Path(os.environ['RUNNER_TEMP'])/'mobile-generator-fixtures.json').read_text())
assert 0<len(commands)<=120 and len(commands)==len(set(commands))
with tempfile.TemporaryDirectory() as work:
    exe=pathlib.Path(work)/'ciadpi';compile_engine(exe)
    for command in commands:
        assert safe(command),command
        ok,error=starts(exe,command)
        assert ok,(command,error)
print(f'{len(commands)} generated candidates started and negotiated SOCKS5 with the pinned engine; this is not proof of ISP effectiveness')

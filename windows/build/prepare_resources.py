#!/usr/bin/env python3
"""Obtain only public trust material; never restores a private signing key."""
import base64,hashlib,json,pathlib,subprocess,tempfile,urllib.request,zipfile

root=pathlib.Path(__file__).resolve().parents[2]
out=root/'windows/Resources';out.mkdir(exist_ok=True)
apk_url='https://github.com/Magral/DPAIAI/releases/download/tv-v0.6.1/DPI-Pilot-TV-0.6.1.apk'
apk_sha='7af5afc8a2bd17b8a1328eec5c6fee79b0d78a23bac031024ab9445f794e2c44'
pin='c445f31d1345b3766a1f269f86f8953997938196eeade34462b61cc7024a8a56'
def fetch(url,limit):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'DPI-Pilot-Windows-build/1'}),timeout=30) as response:
        data=response.read(limit+1)
        if len(data)>limit:raise ValueError('download too large')
        return data
with tempfile.TemporaryDirectory() as t:
    tmp=pathlib.Path(t);apk=tmp/'trust.apk';data=fetch(apk_url,4*1024*1024)
    if hashlib.sha256(data).hexdigest()!=apk_sha:raise ValueError('Pinned public APK digest mismatch')
    apk.write_bytes(data)
    with zipfile.ZipFile(apk) as package:
        names=[n for n in package.namelist() if n.startswith('META-INF/') and n.endswith('.RSA')]
        if len(names)!=1:raise ValueError('Expected one public signer')
        (tmp/'signer.p7b').write_bytes(package.read(names[0]))
    pem=subprocess.check_output(['openssl','pkcs7','-inform','DER','-in',str(tmp/'signer.p7b'),'-print_certs'])
    (tmp/'cert.pem').write_bytes(pem)
    der=subprocess.check_output(['openssl','x509','-in',str(tmp/'cert.pem'),'-outform','DER'])
    if hashlib.sha256(der).hexdigest()!=pin:raise ValueError('Permanent public certificate mismatch')
    (out/'catalog-cert.pem').write_bytes(pem)
    public=subprocess.check_output(['openssl','x509','-in',str(tmp/'cert.pem'),'-pubkey','-noout']);(tmp/'public.pem').write_bytes(public)
    feed=fetch('https://github.com/Magral/DPAIAI/releases/download/strategy-catalog/catalog-v1.json',3*1024*1024)
    envelope=json.loads(feed);payload=base64.b64decode(envelope['payload'],validate=True);signature=base64.b64decode(envelope['signature'],validate=True)
    (tmp/'payload').write_bytes(b'DPI-PILOT-TV-CATALOG-v1\n'+payload);(tmp/'signature').write_bytes(signature)
    subprocess.run(['openssl','dgst','-sha256','-verify',str(tmp/'public.pem'),'-signature',str(tmp/'signature'),str(tmp/'payload')],check=True)
    (out/'catalog-v1.json').write_bytes(feed)
    print('Public certificate pinned; actual catalog signature verified:',len(json.loads(payload)['strategies']),'entries')

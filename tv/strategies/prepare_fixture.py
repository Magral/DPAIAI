#!/usr/bin/env python3
"""Exercise the actual published signed feed with the production Kotlin verifier in CI."""
import hashlib,os,pathlib,subprocess
from refresh_catalog import previous,root

directory=pathlib.Path(os.environ['RUNNER_TEMP'])/'catalog-public-fixture'
directory.mkdir(exist_ok=True)
old=previous(directory)
if old is None:raise RuntimeError('Publish the initial signed data channel before the APK release')
cert=subprocess.run(['keytool','-exportcert','-keystore',os.environ['ANDROID_KEYSTORE_PATH'],
                     '-alias','dpipilot','-storepass:env','ANDROID_KEYSTORE_PASSWORD'],capture_output=True,check=True).stdout
if hashlib.sha256(cert).hexdigest()!=(root/'signing/certificate-sha256.txt').read_text().strip():raise RuntimeError('certificate pin')
(directory/'certificate.der').write_bytes(cert)
with open(os.environ['GITHUB_ENV'],'a') as output:output.write('CATALOG_TEST_FIXTURE='+str(directory)+'\n')
print(f"Public signature fixture ready: {len(old['strategies'])} strategies, sequence {old['sequence']}")

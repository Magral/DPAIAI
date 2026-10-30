#!/usr/bin/env python3
"""Restore the permanent private key from one GitHub Actions secret."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    target = Path(os.environ['RUNNER_TEMP']) / 'dpipilot-release.p12'
    try:
        raw = os.environ.get('DPI_PILOT_SIGNING', '')
        if not raw or len(raw) > 32000:
            raise ValueError()
        bundle = json.loads(raw)
        if bundle.get('schema') != 1:
            raise ValueError()
        password = bundle['store_password']
        encoded = bundle['keystore_base64']
        expected = Path('signing/certificate-sha256.txt').read_text().strip()
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', password):
            raise ValueError()
        if not re.fullmatch(r'[0-9a-f]{64}', expected):
            raise ValueError()
        if bundle['certificate_sha256'] != expected:
            raise ValueError()
        data = base64.b64decode(encoded, validate=True)
        if not 1000 <= len(data) <= 16000:
            raise ValueError()
        print('::add-mask::' + password)
        print('::add-mask::' + encoded)
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
        env = dict(os.environ, DPI_SIGN_STORE_PASSWORD=password)
        cert = subprocess.run([
            'keytool', '-exportcert', '-keystore', str(target),
            '-alias', 'dpipilot', '-storepass:env', 'DPI_SIGN_STORE_PASSWORD',
        ], env=env, capture_output=True, check=True).stdout
        if hashlib.sha256(cert).hexdigest() != expected:
            raise ValueError()
        with open(os.environ['GITHUB_ENV'], 'a') as stream:
            stream.write(f'ANDROID_KEYSTORE_PATH={target}\n')
            stream.write(f'ANDROID_KEYSTORE_PASSWORD={password}\n')
            stream.write(f'ANDROID_SIGNING_CERT_SHA256={expected}\n')
        print('Permanent keystore restored; certificate matches pinned SHA-256.')
    except Exception:
        # Never print a parsing error, password, Base64 or keytool output.
        target.unlink(missing_ok=True)
        print('Invalid or missing DPI_PILOT_SIGNING secret, or permanent certificate mismatch.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

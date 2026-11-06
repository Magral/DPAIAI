"""Validate live fixture availability before shipping a preview-dependent selection rule."""
from pathlib import Path
from urllib.request import Request, urlopen
import re, struct, time

def dimensions(data, mime):
    if mime == "image/jpeg":
        assert data[:3] == b"\xff\xd8\xff" and data[-2:] == b"\xff\xd9", "incomplete JPEG"
        i = 2
        while i+9 < len(data):
            assert data[i] == 255
            while data[i+1] == 255:
                i += 1
            marker = data[i+1]
            size = int.from_bytes(data[i+2:i+4], "big")
            if marker in (192,193,194,195,197,198,199,201,202,203,205,206,207):
                h,w = struct.unpack(">HH",data[i+5:i+9])
                return w,h
            assert size >= 2
            i += 2+size
    elif mime == "image/webp":
        assert data[:4] == b"RIFF" and data[8:12] == b"WEBP"
        assert int.from_bytes(data[4:8],"little")+8 == len(data), "incomplete WebP"
        i = 12
        while i+8 <= len(data):
            kind=data[i:i+4];n=int.from_bytes(data[i+4:i+8],"little");part=data[i+8:i+8+n]
            if kind == b"VP8X":
                return 1+int.from_bytes(part[4:7],"little"),1+int.from_bytes(part[7:10],"little")
            if kind == b"VP8L":
                assert part[0] == 47
                bits=int.from_bytes(part[1:5],"little")
                return 1+(bits & 16383),1+((bits >> 14) & 16383)
            if kind == b"VP8 ":
                assert part[3:6] == b"\x9d\x01\x2a"
                return int.from_bytes(part[6:8],"little") & 16383,int.from_bytes(part[8:10],"little") & 16383
            i += 8+n+(n & 1)
    raise AssertionError("image dimensions missing")

source=Path("tv/src/main/java/io/github/magral/dpipilot/tv/TvProbe.kt").read_text()
fixtures=list(dict.fromkeys(re.findall(r'Spec\("image[^"]*","([^"]+)","([^"]+)"\)',source)))
assert len(fixtures) == 3
for host,path in fixtures:
    url="https://"+host+path
    for attempt in range(3):
        try:
            with urlopen(Request(url,headers={"User-Agent":"DPI-Pilot-fixture-check","Accept-Encoding":"identity"}),timeout=15) as response:
                assert response.status == 200
                data=response.read(512*1024+1)
                mime=response.headers.get("content-type","").split(";")[0]
            assert 1024 <= len(data) <= 512*1024
            w,h=dimensions(data,mime)
            assert 320 <= w <= 1920 and 180 <= h <= 1080, "placeholder or oversized fixture"
            print(f"Fixture OK: {path}; {mime}; {len(data)} bytes; {w}x{h}")
            break
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1)

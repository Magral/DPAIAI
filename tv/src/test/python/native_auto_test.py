"""Actual native engine test: upstream RST must switch group and replay the request."""
import pathlib,subprocess,tempfile,socket,struct,threading,time,re,json
root=pathlib.Path(__file__).resolve().parents[4]
source=root/'tv/src/main/cpp/engine'
profile=json.loads((root/'tv/strategies/catalog.json').read_text())['strategies'][7]['args']
profile='--proto udp --pf 53 --auto none '+profile
assert '--auto torst,ssl_err' in profile


def read_exact(sock,n):
    out=b''
    while len(out)<n:
        part=sock.recv(n-len(out))
        assert part,'Unexpected EOF'
        out+=part
    return out

with tempfile.TemporaryDirectory() as tmp:
    exe=pathlib.Path(tmp)/'byedpi'
    sources=['packets.c','main.c','conev.c','proxy.c','desync.c','mpool.c','extend.c']
    subprocess.run(['cc','-D_DEFAULT_SOURCE','-std=c99','-O2','-I'+str(source),*[str(source/s) for s in sources],'-o',str(exe)],check=True)
    backend=socket.socket();backend.bind(('127.0.0.1',0));backend.listen();backend.settimeout(10)
    proxy=socket.socket();proxy.bind(('127.0.0.1',0));port=proxy.getsockname()[1];proxy.close()
    received=[];errors=[]
    def server():
        try:
            for attempt in range(2):
                client,_=backend.accept()
                with client:
                    client.settimeout(8)
                    data=b''
                    while b'\r\n\r\n' not in data:
                        part=client.recv(4096)
                        assert part
                        data+=part
                    received.append(data)
                    if attempt==0:
                        client.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack('ii',1,0))
                    else:
                        client.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK')
        except Exception as e: errors.append(type(e).__name__+': '+str(e))
    thread=threading.Thread(target=server,daemon=True);thread.start()
    process=subprocess.Popen([str(exe),'--ip','127.0.0.1','--port',str(port),*profile.split()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        connection=None
        for _ in range(100):
            try: connection=socket.create_connection(('127.0.0.1',port),.1);break
            except OSError: time.sleep(.05)
        assert connection,'Native engine failed to start'
        with connection as client:
            client.settimeout(10)
            client.sendall(b'\x05\x01\x00');assert read_exact(client,2)==b'\x05\x00'
            client.sendall(b'\x05\x01\x00\x01\x7f\x00\x00\x01'+struct.pack('!H',backend.getsockname()[1]))
            header=read_exact(client,4);assert header[:2]==b'\x05\x00'
            read_exact(client,{1:4,4:16}[header[3]]+2)
            request=b'GET /native-failover HTTP/1.1\r\nHost: localhost\r\n\r\n'
            client.sendall(request)
            reply=b''
            while b'\r\n\r\nOK' not in reply:
                part=client.recv(4096);assert part,'Native fallback closed client stream';reply+=part
            assert b'200 OK' in reply
        thread.join(2)
        assert not errors,errors
        assert len(received)==2 and received[0]==received[1],received
        print('Native automatic fallback survived upstream RST and replayed the request to the replacement connection')
    finally:
        process.terminate()
        try: process.wait(3)
        except subprocess.TimeoutExpired: process.kill();process.wait()
        backend.close()

    # Candidate processes must never own or terminate the established active stream.
    import socketserver
    class Echo(socketserver.BaseRequestHandler):
        def handle(self):
            try:
                while True:
                    chunk=self.request.recv(65536)
                    if not chunk: return
                    self.request.sendall(chunk)
            except OSError: pass
    echo=socketserver.ThreadingTCPServer(('127.0.0.1',0),Echo)
    echo.daemon_threads=True
    threading.Thread(target=echo.serve_forever,daemon=True).start()
    def launch(profile_args):
        reservation=socket.socket();reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1];reservation.close()
        proc=subprocess.Popen([str(exe),'--ip','127.0.0.1','--port',str(port),*profile_args],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        try:
            for _ in range(100):
                try: sock=socket.create_connection(('127.0.0.1',port),.2);break
                except OSError: time.sleep(.02)
            else: raise AssertionError('Candidate engine startup failed')
            sock.settimeout(5)
            sock.sendall(b'\x05\x01\x00');assert read_exact(sock,2)==b'\x05\x00'
            sock.sendall(b'\x05\x01\x00\x01\x7f\x00\x00\x01'+struct.pack('!H',echo.server_address[1]))
            reply=read_exact(sock,4);assert reply[:2]==b'\x05\x00'
            read_exact(sock,{1:4,4:16}[reply[3]]+2)
            return proc,sock
        except BaseException:
            proc.terminate();proc.wait(5);raise
    active_proc,active=launch(profile.split())
    try:
        for iteration in range(3):
            payload=(b'active-stream-'+bytes([65+iteration]))*2000
            active.sendall(payload);assert read_exact(active,len(payload))==payload
            scout_proc,scout=launch(['--split','1'])
            try:
                scout.sendall(b'scout-check');assert read_exact(scout,11)==b'scout-check'
            finally:
                scout.close();scout_proc.terminate();scout_proc.wait(5)
            # Same active TCP connection must still transfer bytes after candidate teardown.
            active.sendall(payload);assert read_exact(active,len(payload))==payload
        print('Active native stream survived three independent candidate process start/stop cycles')
    finally:
        active.close();active_proc.terminate();active_proc.wait(5)
        echo.shutdown();echo.server_close()

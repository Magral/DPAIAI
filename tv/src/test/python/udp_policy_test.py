"""Imported UDP-fake options must not add packets to the plain DNS forwarding group."""
import pathlib,tempfile,subprocess,socket,struct,time
root=pathlib.Path(__file__).resolve().parents[4]
source=root/'tv/src/main/cpp/engine'
def exact(sock,n):
    out=b''
    while len(out)<n:
        part=sock.recv(n-len(out));assert part;out+=part
    return out
with tempfile.TemporaryDirectory() as tmp:
    exe=pathlib.Path(tmp)/'engine'
    subprocess.run(['cc','-D_DEFAULT_SOURCE','-std=c99','-O2','-I'+str(source),*[str(source/(s+'.c')) for s in ['packets','main','conev','proxy','desync','mpool','extend']],str(source.parent/'quic_guard.c'),'-Wl,--wrap=sendto','-Wl,--wrap=connect','-o',str(exe)],check=True)
    dns=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);dns.bind(('127.0.0.1',0));dns.settimeout(3)
    reservation=socket.socket();reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1];reservation.close()
    # An unprivileged ephemeral port exercises the same --pf policy as production DNS port 53.
    proc=subprocess.Popen([str(exe),'--ip','127.0.0.1','--port',str(port),'--proto','udp','--pf',str(dns.getsockname()[1]),'--auto','none','--udp-fake','2','--ttl','64'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    tcp=None
    try:
        for _ in range(100):
            try:tcp=socket.create_connection(('127.0.0.1',port),.1);break
            except OSError:time.sleep(.02)
        assert tcp;tcp.settimeout(3)
        tcp.sendall(b'\x05\x01\x00');assert exact(tcp,2)==b'\x05\x00'
        tcp.sendall(b'\x05\x03\x00\x01\x7f\x00\x00\x01\x00\x00')
        header=exact(tcp,4);assert header[:2]==b'\x05\x00'
        assert header[3]==1,header
        address=socket.inet_ntoa(exact(tcp,4));relay_port=struct.unpack('!H',exact(tcp,2))[0]
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as client:
            client.bind(('127.0.0.1',0));client.settimeout(3)
            payload=b'dns-must-not-be-faked'
            client.sendto(b'\x00\x00\x00\x01\x7f\x00\x00\x01'+struct.pack('!H',dns.getsockname()[1])+payload,(address,relay_port))
            data,sender=dns.recvfrom(4096);assert data==payload,(data,payload)
            dns.sendto(data,sender)
            reply=client.recv(4096);assert reply.endswith(payload),reply
            dns.settimeout(.2)
            try:dns.recvfrom(4096);raise AssertionError('DNS group emitted extra/fake datagrams')
            except socket.timeout:pass
        print('Plain UDP forwarding group preserved request/reply and emitted no imported fake packets')
    finally:
        if tcp:tcp.close()
        dns.close();proc.terminate()
        try:proc.wait(2)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()

"""Test exact Kotlin-generated dual-profile argv through production JNI, with numeric SOCKS destinations.
HTTP Host and TLS SNI must select different groups concurrently on the same upstream IP/port.
"""
import concurrent.futures,json,os,pathlib,queue,socket,ssl,subprocess,tempfile,threading,time
root=pathlib.Path(__file__).resolve().parents[4]
java=pathlib.Path(os.environ['JAVA_HOME'])
fixtures=json.loads((pathlib.Path(os.environ['RUNNER_TEMP'])/'mobile-dual-fixtures.json').read_text())
source=root/'tv/src/main/cpp'

def recv_exact(peer,n):
    out=b''
    while len(out)<n:
        part=peer.recv(n-len(out))
        if not part:raise EOFError('Truncated SOCKS reply')
        out+=part
    return out

def socks(peer,port):
    peer.settimeout(5);peer.sendall(b'\x05\x01\x00');assert recv_exact(peer,2)==b'\x05\x00'
    peer.sendall(b'\x05\x01\x00\x01\x7f\x00\x00\x01'+port.to_bytes(2,'big'))
    reply=recv_exact(peer,4);assert reply[:2]==b'\x05\x00',reply
    length={1:4,4:16}.get(reply[3])
    if reply[3]==3:length=recv_exact(peer,1)[0]
    recv_exact(peer,length+2)

def hello(host):
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    incoming,outgoing=ssl.MemoryBIO(),ssl.MemoryBIO()
    tls=ctx.wrap_bio(incoming,outgoing,server_side=False,server_hostname=host)
    try:tls.do_handshake()
    except ssl.SSLWantReadError:pass
    return outgoing.read()

def records(data):
    pos=0;body=b'';lengths=[]
    while pos+5<=len(data):
        n=int.from_bytes(data[pos+3:pos+5],'big')
        if pos+5+n>len(data):break
        body+=data[pos+5:pos+5+n];lengths.append(n);pos+=5+n
    return body,lengths,pos

with tempfile.TemporaryDirectory() as work:
    work=pathlib.Path(work);lib=work/'libmobilepilot.so'
    units=[source/'engine'/(n+'.c') for n in ['packets','main','conev','proxy','desync','mpool','extend']]
    subprocess.run(['cc','-shared','-fPIC','-D_DEFAULT_SOURCE','-std=c99','-O2','-I'+str(source/'engine'),'-I'+str(java/'include'),'-I'+str(java/'include/linux'),*[str(p) for p in units],str(root/'app/src/main/cpp/mobile_jni.c'),str(source/'quic_guard.c'),'-Wl,--wrap=sendto','-Wl,--wrap=connect','-o',str(lib)],check=True)
    directory=work/'io/github/dovecoteescapee/byedpi/core';directory.mkdir(parents=True)
    test=directory/'MobileProxy.java'
    test.write_text('''package io.github.dovecoteescapee.byedpi.core;
public class MobileProxy {
 private native int jniCreateSocketWithCommandLine(String[] args);
 private native int jniStartProxy(int fd);
 public static void main(String[] args) throws Exception {
  System.load(args[0]); MobileProxy p=new MobileProxy();
  int fd=p.jniCreateSocketWithCommandLine(java.util.Arrays.copyOfRange(args,1,args.length));
  if(fd<0) throw new IllegalStateException("Dual plan rejected by native parser");
  Thread worker=new Thread(()->p.jniStartProxy(fd));worker.setDaemon(true);worker.start();System.in.read();
 }
}''')
    subprocess.run([str(java/'bin/javac'),str(test)],check=True)
    for mode,args in fixtures.items():
        reservation=socket.socket();reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1];reservation.close()
        args[4]=str(port)
        process=subprocess.Popen([str(java/'bin/java'),'-cp',str(work),'io.github.dovecoteescapee.byedpi.core.MobileProxy',str(lib),*args],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        try:
            ready=False
            for _ in range(100):
                if process.poll() is not None:break
                try:
                    with socket.create_connection(('127.0.0.1',port),.1) as peer:
                        peer.settimeout(1);peer.sendall(b'\x05\x01\x00');ready=recv_exact(peer,2)==b'\x05\x00'
                    if ready:break
                except OSError:time.sleep(.03)
            assert ready,'Dual engine did not start'
            hosts=['www.instagram.com','rr1---sn-test.googlevideo.com','example.org','youtube.com.evil.invalid']
            payloads={host:(f'GET / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n'.encode() if mode=='http' else hello(host)) for host in hosts}
            count=8 if mode=='http' else 4
            captured=queue.Queue()
            with socket.socket() as backend,concurrent.futures.ThreadPoolExecutor(max_workers=20) as workers:
                backend.bind(('127.0.0.1',0));backend.listen(16);backend.settimeout(10);upstream=backend.getsockname()[1]
                def receive(peer):
                    with peer:
                        peer.settimeout(5);data=b''
                        while True:
                            chunk=peer.recv(8192)
                            if not chunk:raise EOFError('Missing routed data')
                            data+=chunk
                            if mode=='http':
                                if b'\r\n\r\n' in data:break
                            else:
                                body,_,used=records(data)
                                if len(body)>=4 and len(body)>=4+int.from_bytes(body[1:4],'big') and used==len(data):break
                        host=next(h for h in hosts if h.encode() in data)
                        captured.put((host,data))
                        peer.sendall(b'HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n' if mode=='http' else b'\x15\x03\x03\x00\x02\x02\x28')
                def accept_all():
                    pending=[workers.submit(receive,backend.accept()[0]) for _ in range(count)]
                    for future in pending:future.result(timeout=10)
                accepting=workers.submit(accept_all)
                def send(host):
                    with socket.create_connection(('127.0.0.1',port),2) as peer:
                        socks(peer,upstream);peer.sendall(payloads[host]);assert peer.recv(1024)
                sending=[workers.submit(send,hosts[i%len(hosts)]) for i in range(count)]
                for future in sending:future.result(timeout=15)
                accepting.result(timeout=15)
                for _ in range(count):
                    host,data=captured.get(timeout=2)
                    if mode=='http':
                        if host=='www.instagram.com':assert b'hOsT: '+host.encode()+b'\r\n' in data,data
                        elif host.endswith('googlevideo.com'):assert b'Host:'+host.encode()+b'\t\r\n' in data,data
                        else:assert data==payloads[host],(host,data)
                    else:
                        original,_,_=records(payloads[host]);actual,lengths,_=records(data)
                        assert original==actual,'TLS handshake bytes changed'
                        if host=='www.instagram.com':assert len(lengths)>1 and lengths[0]==1,lengths
                        else:assert data==payloads[host],(host,lengths)
            process.communicate(input=b'\n',timeout=5)
            assert process.returncode==0
            print(f'Dual profile {mode}: {count} concurrent numeric-IP SOCKS flows correctly selected Instagram, YouTube or unchanged fallback')
        finally:
            if process.poll() is None:process.kill();process.communicate()

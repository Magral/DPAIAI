"""Real router + real JNI backends. Numeric SOCKS targets, public Host/SNI, hot swap and rollback."""
import concurrent.futures,os,pathlib,queue,socket,subprocess,threading,time

def check_router(root,work,lib,java,fixtures,hello,records,recv_exact):
    gradle=pathlib.Path(os.environ.get('GRADLE_USER_HOME',str(pathlib.Path.home()/'.gradle')))
    jars=list((gradle/'caches/modules-2/files-2.1/org.jetbrains.kotlin').glob('kotlin-stdlib*/*/*/*.jar'))
    classes=next((root/'app/build/tmp/kotlin-classes'/variant for variant in ('releaseUnitTest','debugUnitTest') if (root/'app/build/tmp/kotlin-classes'/variant).exists()))
    main=classes.parent/('release' if classes.name.startswith('release') else 'debug')
    classpath=os.pathsep.join(map(str,[classes,main,*jars]));assert jars,'Kotlin runtime missing'
    children=[]
    def native(key):
        reserve=socket.socket();reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1];reserve.close()
        args=list(fixtures[key]);args[4]=str(port)
        process=subprocess.Popen([str(java/'bin/java'),'-cp',str(work),'io.github.dovecoteescapee.byedpi.core.MobileProxy',str(lib),*args],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        children.append(process)
        for _ in range(100):
            if process.poll() is not None:break
            try:
                with socket.create_connection(('127.0.0.1',port),.1) as peer:
                    peer.settimeout(1);peer.sendall(b'\x05\x01\x00');assert recv_exact(peer,2)==b'\x05\x00';return port
            except OSError:time.sleep(.03)
        raise AssertionError('Independent JNI backend failed')
    def router(ig,yt):
        process=subprocess.Popen([str(java/'bin/java'),'-cp',classpath,'io.github.dovecoteescapee.byedpi.RouterHarness',str(ig),str(yt)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        children.append(process);ready=process.stdout.readline().strip();assert ready.startswith('READY '),(ready,process.stderr.read() if process.poll() is not None else '')
        return process,int(ready.split()[1])
    def connect(port,target):
        peer=socket.create_connection(('127.0.0.1',port),2);peer.settimeout(8)
        peer.sendall(b'\x05\x01\x00');assert recv_exact(peer,2)==b'\x05\x00'
        peer.sendall(b'\x05\x01\x00\x01\x7f\x00\x00\x01'+target.to_bytes(2,'big'))
        assert recv_exact(peer,2)==b'\x05\x00';recv_exact(peer,8);return peer
    def swap(process,port):
        process.stdin.write('youtube '+str(port)+'\n');process.stdin.flush();assert process.stdout.readline().strip()=='SWAPPED'
    def cleanup():
        for process in reversed(children):
            if process.poll() is None:
                process.stdin.close()
                try:process.wait(timeout=3)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            process.stderr.close()
    try:
        ig=native('router_instagram');yt=native('router_youtube');new=native('router_youtube_next')
        process,port=router(ig,yt)
        captured=queue.Queue();stop=threading.Event()
        with socket.socket() as backend,concurrent.futures.ThreadPoolExecutor(max_workers=12) as workers:
            backend.bind(('127.0.0.1',0));backend.listen(12);backend.settimeout(.2);target=backend.getsockname()[1]
            def handle(peer,number):
                with peer:
                    peer.settimeout(8);stream=peer.makefile('rb')
                    while True:
                        request=stream.readline()
                        if not request:break
                        headers=[]
                        while True:
                            line=stream.readline()
                            if line in (b'',b'\r\n'):break
                            headers.append(line)
                        captured.put((number,b''.join(headers)))
                        body=str(number).encode();peer.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(len(body)).encode()+b'\r\n\r\n'+body)
            def accept():
                number=0
                while not stop.is_set():
                    try:peer,_=backend.accept()
                    except socket.timeout:continue
                    number+=1;workers.submit(handle,peer,number)
            accepting=workers.submit(accept)
            def request(peer,host):
                peer.sendall(('GET / HTTP/1.1\r\nHost: '+host+'\r\n\r\n').encode())
                data=b''
                while b'\r\n\r\n' not in data:data+=recv_exact(peer,1)
                length=int(next(line.split(b':',1)[1] for line in data.split(b'\r\n') if line.lower().startswith(b'content-length:')))
                return int(recv_exact(peer,length))
            with connect(port,target) as ig_peer,connect(port,target) as old_peer:
                ig_id=request(ig_peer,'www.instagram.com');_,headers=captured.get(timeout=3);assert b'hOsT: www.instagram.com' in headers,headers
                yt_id=request(old_peer,'www.youtube.com');_,headers=captured.get(timeout=3);assert b'Host:www.youtube.com\t' in headers,headers
                swap(process,new)
                assert request(ig_peer,'www.instagram.com')==ig_id;captured.get(timeout=3)
                assert request(old_peer,'www.youtube.com')==yt_id;captured.get(timeout=3)
                with connect(port,target) as next_peer:
                    assert request(next_peer,'www.youtube.com')!=yt_id
                    _,headers=captured.get(timeout=3);assert b'hOsT: www.youtube.com' in headers,headers
                swap(process,yt)
                with connect(port,target) as back:
                    request(back,'www.youtube.com');_,headers=captured.get(timeout=3);assert b'Host:www.youtube.com\t' in headers,headers
            stop.set();accepting.result(timeout=3)
        print('Production router + independent JNI: Instagram and existing YouTube flows survived YouTube hot swap; new route and rollback selected correct engines')
        # Full TLS chain through the same production inspector and separate native backends.
        ig_tls=native('router_instagram_tls');yt_tls=native('router_youtube_tls');tls_process,tls_port=router(ig_tls,yt_tls)
        hosts=['www.instagram.com','r1.googlevideo.com'];payloads={host:hello(host) for host in hosts};packets=queue.Queue()
        with socket.socket() as backend,concurrent.futures.ThreadPoolExecutor(max_workers=6) as workers:
            backend.bind(('127.0.0.1',0));backend.listen(4);target=backend.getsockname()[1]
            def receive(peer):
                with peer:
                    peer.settimeout(8);data=b''
                    while True:
                        part=peer.recv(8192)
                        if not part:raise EOFError('TLS prefix lost')
                        data+=part;body,lengths,used=records(data)
                        if len(body)>=4 and len(body)>=4+int.from_bytes(body[1:4],'big') and used==len(data):break
                    packets.put((next(host for host in hosts if host.encode() in body),data,lengths))
                    peer.sendall(b'\x15\x03\x03\x00\x02\x02\x28')
            def accept_all():
                futures=[workers.submit(receive,backend.accept()[0]) for _ in hosts]
                for future in futures:future.result(timeout=12)
            accepting=workers.submit(accept_all)
            def send(host):
                with connect(tls_port,target) as peer:peer.sendall(payloads[host]);assert peer.recv(1024)
            sending=[workers.submit(send,host) for host in hosts]
            for future in sending:future.result(timeout=15)
            accepting.result(timeout=15)
            for _ in hosts:
                host,data,lengths=packets.get(timeout=3)
                assert records(data)[0]==records(payloads[host])[0]
                if host=='www.instagram.com':assert len(lengths)>1 and lengths[0]==1,lengths
                else:assert data==payloads[host]
        print('Production router + independent JNI: numeric-IP TLS connections correctly routed by SNI; exact encrypted handshake payload preserved')
    finally:cleanup()

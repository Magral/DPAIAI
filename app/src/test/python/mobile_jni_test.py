"""Exercise the exact Mobile JNI entry points against the pinned native engine on the CI JVM."""
import pathlib,subprocess,tempfile,os,socket,time
root=pathlib.Path(__file__).resolve().parents[4]
java=pathlib.Path(os.environ['JAVA_HOME'])
source=root/'tv/src/main/cpp'
with tempfile.TemporaryDirectory() as tmp:
    tmp=pathlib.Path(tmp);lib=tmp/'libmobilepilot.so'
    units=[source/'engine'/(name+'.c') for name in ['packets','main','conev','proxy','desync','mpool','extend']]
    subprocess.run(['cc','-shared','-fPIC','-D_DEFAULT_SOURCE','-std=c99','-O2','-I'+str(source/'engine'),'-I'+str(java/'include'),'-I'+str(java/'include/linux'),*[str(p) for p in units],str(root/'app/src/main/cpp/mobile_jni.c'),str(source/'quic_guard.c'),'-Wl,--wrap=sendto','-Wl,--wrap=connect','-o',str(lib)],check=True)
    directory=tmp/'io/github/dovecoteescapee/byedpi/core';directory.mkdir(parents=True)
    test=directory/'MobileProxy.java'
    test.write_text('''package io.github.dovecoteescapee.byedpi.core;
public class MobileProxy {
 private native int jniCreateSocketWithCommandLine(String[] args);
 private native int jniStartProxy(int fd);
 public static void main(String[] args) throws Exception {
  System.load(args[0]);
  MobileProxy p=new MobileProxy();
  int fd=p.jniCreateSocketWithCommandLine(new String[]{"byedpi","--ip","127.0.0.1","--port",args[1],"--proto","udp","--pf","53","--auto","none","--split","1","--fake-tls-mod","rand","--auto","torst,ssl_err,conn","--disorder","1"});
  if(fd<0) throw new IllegalStateException("JNI parser/listener failed");
  Thread worker=new Thread(()->p.jniStartProxy(fd));worker.setDaemon(true);worker.start();
  System.out.println("READY");System.out.flush();System.in.read();
 }
}''')
    subprocess.run([str(java/'bin/javac'),str(test)],check=True)
    reservation=socket.socket();reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1];reservation.close()
    process=subprocess.Popen([str(java/'bin/java'),'-cp',str(tmp),'io.github.dovecoteescapee.byedpi.core.MobileProxy',str(lib),str(port)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    try:
        success=False
        for _ in range(100):
            if process.poll() is not None:break
            try:
                with socket.create_connection(('127.0.0.1',port),.1) as client:
                    client.settimeout(2);client.sendall(b'\x05\x01\x00');assert client.recv(2)==b'\x05\x00';success=True;break
            except OSError:time.sleep(.03)
        assert success,'JNI engine did not negotiate SOCKS5'
        process.communicate(input=b'\n',timeout=5)
        assert process.returncode==0,'JNI test JVM failed'
        print('Mobile JNI command parser and event loop negotiated SOCKS5 through original class entry points')
    finally:
        if process.poll() is None:process.kill();process.communicate()

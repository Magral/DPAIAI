package io.github.magral.dpipilot.tv

import android.os.Build
import io.github.dovecoteescapee.byedpi.utility.ProbeDeadline
import io.github.dovecoteescapee.byedpi.utility.ProbeSession
import io.github.dovecoteescapee.byedpi.utility.TargetProbe
import java.io.BufferedInputStream
import java.net.*
import java.util.concurrent.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Explicit, bounded requests. No cookies, redirects, DNS/settings changes or TLS bypass. */
object PlaybackHttp {
    data class Request(val host: String,val path: String="/",val proxyPort: Int?=null,val body: String?=null,val limit: Int=4096,val range: Boolean=false,val timeoutMs: Int=12000)
    private val dnsPool=Executors.newFixedThreadPool(2) { r -> Thread(r,"playback-dns").apply { isDaemon=true } }
    fun resolve(host: String,session: ProbeSession): List<String> = addresses(host,session).mapNotNull {it.hostAddress}
    private fun addresses(host: String,session: ProbeSession,network: android.net.Network?=null): List<InetAddress> {
        session.checkActive()
        val future=dnsPool.submit(Callable { (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList() })
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6)
        try {
            while(System.nanoTime()<end) {
                session.checkActive()
                try {return future.get(200,TimeUnit.MILLISECONDS)}catch(_: TimeoutException) { }
            }
            throw SocketTimeoutException("DNS deadline")
        } finally {future.cancel(true)}
    }
    fun allowed(host: String): Boolean = host in listOf("www.youtube.com","youtubei.googleapis.com","redirector.googlevideo.com","www.gstatic.com","dns.google") ||
        Regex("[a-zA-Z0-9.-]+\\.googlevideo\\.com").matches(host)
    fun request(req: Request,session: ProbeSession,progress: (String)->Unit): HttpCodec.Response = perform(null,req,session,progress)
    /** Explicit VPN network: DNS and sockets cannot fall back to a physical network. */
    fun requestOn(network: android.net.Network,req: Request,session: ProbeSession,progress: (String)->Unit): HttpCodec.Response {
        require(req.proxyPort==null)
        return perform(network,req,session,progress)
    }
    fun resolveOn(network: android.net.Network,host: String,session: ProbeSession): List<String> = addresses(host,session,network).mapNotNull {it.hostAddress}
    private fun perform(network: android.net.Network?,req: Request,session: ProbeSession,progress: (String)->Unit): HttpCodec.Response {
        require(allowed(req.host) && req.path.startsWith('/') && req.path.none {it.isISOControl()} && req.path.length<=8192)
        require(req.limit in 1..2*1024*1024 && (req.proxyPort==null || req.proxyPort in 1024..65535))
        require(req.timeoutMs in 1000..60000)
        val deadline=ProbeDeadline(req.timeoutMs.toLong()+6000)
        var raw: Socket?=null
        try {
            session.checkActive()
            if(req.proxyPort!=null) {
                progress("${req.host}: SOCKS / TCP")
                raw=TargetProbe.socket(req.host,443,req.proxyPort,req.timeoutMs,{session.register(it);deadline.register(it)})
            } else {
                progress("${req.host}: DNS / TCP")
                val addresses=addresses(req.host,session,network)
                var last: Exception?=null
                for(address in addresses.take(3)) {
                    session.checkActive();deadline.check()
                    val socket=if(network==null) Socket(Proxy.NO_PROXY) else network.socketFactory.createSocket()
                    session.register(socket);deadline.register(socket)
                    try {socket.connect(InetSocketAddress(address,443),4000);raw=socket;break}
                    catch(e: Exception) {last=e;socket.close()}
                }
                if(raw==null)throw last ?: UnknownHostException(req.host)
            }
            val socket=raw
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,req.host,443,true) as SSLSocket).use { tls ->
                tls.soTimeout=req.timeoutMs;session.register(tls);deadline.register(tls)
                if(Build.VERSION.SDK_INT>=24)tls.sslParameters=tls.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS"}
                progress("${req.host}: TLS");tls.startHandshake()
                if(Build.VERSION.SDK_INT<24 && !HttpsURLConnection.getDefaultHostnameVerifier().verify(req.host,tls.session))throw java.io.IOException("TLS hostname mismatch")
                session.checkActive();deadline.check()
                val body=req.body?.toByteArray(Charsets.UTF_8)
                val method=if(body==null)"GET" else "POST"
                val headers="$method ${req.path} HTTP/1.1\r\nHost: ${req.host}\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n"+
                    (if(req.range)"Range: bytes=0-${req.limit-1}\r\n" else "")+
                    (if(body!=null)"Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" else "")+"\r\n"
                tls.outputStream.write(headers.toByteArray(Charsets.US_ASCII));if(body!=null)tls.outputStream.write(body);tls.outputStream.flush()
                progress("${req.host}: HTTP / тело ответа")
                return HttpCodec.read(BufferedInputStream(tls.inputStream),req.limit,{session.checkActive();deadline.check()}).also {session.checkActive();deadline.check()}
            }
        } catch(e: Exception) {session.checkActive();deadline.check();throw e}
        finally {raw?.close();deadline.close()}
    }
}

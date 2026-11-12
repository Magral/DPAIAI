package io.github.dovecoteescapee.byedpi.utility

import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/** Application-local resolver. No system DNS settings are changed. Empty resolver means network DNS. */
object DnsResolver {
    data class Provider(val name: String,val address: String,val source: String)
    val providers = listOf(
        Provider("DNS сети","",""),
        Provider("Cloudflare","1.1.1.1","https://developers.cloudflare.com/1.1.1.1/ip-addresses/"),
        Provider("Cloudflare DoH","https://cloudflare-dns.com/dns-query","https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/"),
        Provider("Google","8.8.8.8","https://developers.google.com/speed/public-dns/docs/using"),
        Provider("Google DoH","https://dns.google/dns-query","https://developers.google.com/speed/public-dns/docs/doh"),
        Provider("AdGuard без фильтрации","94.140.14.140","https://adguard-dns.io/en/public-dns.html"),
        Provider("AdGuard DoH без фильтрации","https://unfiltered.adguard-dns.com/dns-query","https://adguard-dns.io/en/public-dns.html"),
        Provider("Quad9 без фильтрации","9.9.9.10","https://docs.quad9.net/services/"),
        Provider("Quad9 DoH без фильтрации","https://dns10.quad9.net/dns-query","https://docs.quad9.net/services/"),
        Provider("Control D без фильтрации","76.76.2.0","https://docs.controld.com/docs/free-dns"),
        Provider("Control D DoH без фильтрации","https://freedns.controld.com/p0","https://docs.controld.com/docs/free-dns"),
        Provider("DNS.SB","185.222.222.222","https://dns.sb/servers/"),
        Provider("DNS.SB DoH","https://doh.dns.sb/dns-query","https://dns.sb/doh/"),
        Provider("DNS4EU без фильтрации","86.54.11.100","https://joindns4.eu/for-public"),
        Provider("DNS4EU DoH без фильтрации","https://unfiltered.joindns4.eu/dns-query","https://joindns4.eu/for-public"),
        Provider("Яндекс базовый","77.88.8.8","https://dns.yandex.ru/"))
    private data class Cached(val ip: String,val until: Long)
    private val cache = ConcurrentHashMap<String,Cached>()
    fun valid(server: String) = server.isEmpty() || server.split('.').let { it.size == 4 && it.all { n -> n.toIntOrNull() in 0..255 } } ||
        runCatching { val u=URI(server);u.scheme=="https" && !u.host.isNullOrEmpty() && u.userInfo==null && u.fragment==null }.getOrDefault(false)
    fun clear() = cache.clear()
    fun resolve(host: String,server: String): String {
        require(valid(server)) { "Invalid DNS resolver" }
        if (host.split('.').let { it.size==4 && it.all { n -> n.toIntOrNull() in 0..255 } }) return host
        if (server.isEmpty()) return InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress ?: throw UnknownHostException(host)
        val key="$server|$host";val now=System.nanoTime()
        cache[key]?.takeIf { now < it.until }?.let { return it.ip }
        val query=query(host,java.security.SecureRandom().nextInt(65536))
        val response = if (server.startsWith("https://")) doh(server,query) else udp(server,query)
        val answer=answer(response,query)
        if (cache.size >= 256) cache.clear()
        cache[key]=Cached(answer.first,now+answer.second.coerceIn(1,300)*1000000000L)
        return answer.first
    }
    internal fun query(host: String,id: Int): ByteArray {
        val out=ByteArrayOutputStream();val d=DataOutputStream(out)
        d.writeShort(id);d.writeShort(0x100);d.writeShort(1);repeat(3) { d.writeShort(0) }
        val ascii=IDN.toASCII(host.trimEnd('.'));require(ascii.length in 1..253)
        ascii.split('.').forEach { label -> require(label.length in 1..63);d.writeByte(label.length);d.writeBytes(label) }
        d.writeByte(0);d.writeShort(1);d.writeShort(1);return out.toByteArray()
    }
    fun questionName(query: ByteArray): String? = runCatching {
        val d=DataInputStream(ByteArrayInputStream(query));d.readUnsignedShort();require(d.readUnsignedShort() and 0x8000==0);require(d.readUnsignedShort()==1);d.skipBytes(6)
        val labels=mutableListOf<String>();var length=0
        while(true) { val n=d.readUnsignedByte();if(n==0)break;require(n in 1..63);length+=n+1;require(length<=254)
            labels.add(String(ByteArray(n).also { d.readFully(it) },Charsets.US_ASCII)) }
        labels.joinToString(".")
    }.getOrNull()
    fun exchange(query: ByteArray,server: String): ByteArray {
        require(server.isNotEmpty() && valid(server));require(questionName(query)!=null)
        val response=if(server.startsWith("https://"))doh(server,query) else udp(server,query)
        require(response.size>=12 && response[0]==query[0] && response[1]==query[1] && response[2].toInt() and 0x80!=0)
        return response
    }
    private fun doh(url: String,query: ByteArray): ByteArray {
        val c=URL(url).openConnection(Proxy.NO_PROXY) as HttpsURLConnection
        try {
            c.connectTimeout=2000;c.readTimeout=2000;c.instanceFollowRedirects=false;c.requestMethod="POST";c.doOutput=true
            c.setRequestProperty("Content-Type","application/dns-message");c.setRequestProperty("Accept","application/dns-message")
            c.setFixedLengthStreamingMode(query.size);c.outputStream.use { it.write(query) }
            check(c.responseCode==200 && c.contentType?.substringBefore(';')=="application/dns-message") { "DoH response" }
            return c.inputStream.use { bounded(it) }
        } finally { c.disconnect() }
    }
    private fun bounded(input: InputStream): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(2048)
        while (true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=65535);out.write(buffer,0,n) };return out.toByteArray()
    }
    private fun udp(server: String,query: ByteArray): ByteArray {
        val reply=DatagramSocket().use { s ->
            s.soTimeout=1500;s.connect(InetAddress.getByName(server),53);s.send(DatagramPacket(query,query.size))
            val p=DatagramPacket(ByteArray(65535),65535);s.receive(p);p.data.copyOf(p.length)
        }
        if(reply.size>=4 && reply[2].toInt() and 2 != 0) return Socket(Proxy.NO_PROXY).use { s ->
            s.connect(InetSocketAddress(server,53),1500);s.soTimeout=1500
            DataOutputStream(s.outputStream).apply { writeShort(query.size);write(query);flush() }
            val d=DataInputStream(s.inputStream);ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
        }
        return reply
    }
    internal fun answer(bytes: ByteArray,query: ByteArray): Pair<String,Long> {
        val d=DataInputStream(ByteArrayInputStream(bytes))
        require(d.readUnsignedShort()==((query[0].toInt() and 255) shl 8 or (query[1].toInt() and 255)))
        val flags=d.readUnsignedShort();require(flags and 0x8000!=0 && flags and 0x020f==0)
        require(d.readUnsignedShort()==1);val count=d.readUnsignedShort();d.readInt()
        val question=ByteArray(query.size-12);d.readFully(question);require(question.contentEquals(query.copyOfRange(12,query.size)))
        fun skipName() { var labels=0;while(true) { require(++labels<=128);val n=d.readUnsignedByte();if(n==0)return;if(n and 0xc0==0xc0) { d.readUnsignedByte();return };require(n<=63);repeat(n) { d.readByte() } } }
        repeat(count) {
            skipName();val type=d.readUnsignedShort();val clazz=d.readUnsignedShort();val ttl=d.readInt().toLong() and 0xffffffffL;val n=d.readUnsignedShort()
            val value=ByteArray(n);d.readFully(value)
            if(type==1 && clazz==1 && n==4) {
                val ip=InetAddress.getByAddress(value);require(!ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isMulticastAddress && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress)
                return ip.hostAddress!! to ttl
            }
        }
        throw UnknownHostException("No public IPv4 answer")
    }
}

package io.github.dovecoteescapee.byedpi.warp

import org.json.JSONObject
import org.json.JSONArray
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/** Bounded config; no shell hooks, filenames, custom routes or public key material in toString. */
class WarpProfile private constructor(val privateKey: String,val peerKey: String,val addresses: List<String>,val endpoint: String,
    val reserved: List<Int>,val options: Map<String,String>) {
    fun privateHex()=WarpEncoding.decode(privateKey).joinToString("") {"%02x".format(it.toInt() and 255)}
    fun ipc(overrideEndpoint: String=endpoint)=buildString {
        require(providerEndpoint(overrideEndpoint))
        append("private_key=${privateHex()}\nreplace_peers=true\n")
        for((key,value) in options)append("${key.lowercase()}=$value\n")
        val peer=WarpEncoding.decode(peerKey).joinToString("") {"%02x".format(it.toInt() and 255)}
        append("public_key=$peer\nendpoint=$overrideEndpoint\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=25\n")
    }
    fun encode(): String=JSONObject().put("schema",1).put("private",privateKey).put("peer",peerKey).put("addresses",JSONArray(addresses))
        .put("endpoint",endpoint).put("reserved",JSONArray(reserved)).put("options",JSONObject(options)).toString()
    override fun toString()="WARP profile (keys hidden; $endpoint)"
    companion object {
        private val numericOptions=mapOf("Jc" to 0..20,"Jmin" to 0..1280,"Jmax" to 0..1280,"S1" to 0..1024,"S2" to 0..1024,"S3" to 0..1024,"S4" to 0..1024)
        private val headerOptions=setOf("H1","H2","H3","H4")
        private val packetOptions=setOf("I1","I2","I3","I4","I5")
        private fun key(value: String): String {require(value.length==44 && WarpEncoding.decode(value).let {it.size==32 && it.any {b->b!=0.toByte()}}) {"Неверный ключ WARP"};return value}
        fun providerEndpoint(value: String): Boolean {
            if(!Regex("[0-9.]{7,15}:[0-9]{1,5}").matches(value))return false
            val parts=value.substringBefore(':').split('.').map {it.toIntOrNull() ?: return false}
            if(parts.size!=4 || parts.any {it !in 0..255} || parts.joinToString(".")!=value.substringBefore(':'))return false
            val port=value.substringAfter(':').toIntOrNull() ?: return false
            return port in 1..65535 && ((parts[0]==162 && parts[1]==159 && parts[2] in 192..193) || (parts[0]==188 && parts[1]==114 && parts[2] in 96..99))
        }
        private fun address(value: String): Boolean {
            val ip=value.substringBefore('/');val prefix=value.substringAfter('/',"").toIntOrNull() ?: return false
            if(ip.contains(':'))return Regex("[0-9A-Fa-f:]{2,64}").matches(ip) && prefix in 1..128 && runCatching {InetAddress.getByName(ip).address.size==16}.getOrDefault(false)
            val fields=ip.split('.');return fields.size==4 && fields.all {(it.toIntOrNull() ?: -1) in 0..255} && fields.joinToString("."){it.toInt().toString()}==ip && prefix in 1..32
        }
        private fun create(secret: String,peer: String,addresses: List<String>,endpoint: String,reserved: List<Int>,options: Map<String,String>): WarpProfile {
            require(addresses.size in 1..2 && addresses.all(::address) && addresses.any {!it.contains(':')} && providerEndpoint(endpoint)) {"Неверный адрес или сервер Cloudflare WARP"}
            require(reserved.size==3 && reserved.all {it in 0..255}) {"Неверные Reserved байты"}
            for((name,value) in options) {
                when(name) {
                    in numericOptions -> require(value.toIntOrNull() in numericOptions.getValue(name)) {"Неверный параметр WARP: $name"}
                    in headerOptions -> require(Regex("[0-9]{1,10}(-[0-9]{1,10})?").matches(value) && value.split('-').all {it.toLong() in 1..4294967295L}) {"Неверный заголовок WARP"}
                    in packetOptions -> require(value.length in 1..4096 && value.all {it in ' '..'~'} && value.startsWith('<') && value.endsWith('>') && !value.contains('=')) {"Неверный I-пакет WARP"}
                    else -> throw IllegalArgumentException("Неподдерживаемый параметр WARP")
                }
            }
            require((options["Jmin"]?.toInt() ?: 0)<=(options["Jmax"]?.toInt() ?: 1280))
            return WarpProfile(key(secret),key(peer),addresses,endpoint,reserved,options)
        }
        fun decode(value: String): WarpProfile {
            require(value.length in 1..16384)
            val j=JSONObject(value);require(j.getInt("schema")==1)
            val a=j.getJSONArray("addresses");val r=j.getJSONArray("reserved");val opts=j.getJSONObject("options")
            return create(j.getString("private"),j.getString("peer"),(0 until a.length()).map {a.getString(it)},j.getString("endpoint"),(0 until r.length()).map {r.getInt(it)},opts.keys().asSequence().associateWith {opts.getString(it)})
        }
        fun enrolled(secret: String,response: JSONObject): WarpProfile {
            val root=response.optJSONObject("result") ?: response
            val c=root.getJSONObject("config");val a=c.getJSONObject("interface").getJSONObject("addresses")
            val peer=c.getJSONArray("peers").getJSONObject(0);val r=WarpEncoding.decode(c.getString("client_id"));require(r.size==3)
            return create(secret,peer.getString("public_key"),listOfNotNull(a.optString("v4").takeIf {it.isNotEmpty()}?.plus("/32"),a.optString("v6").takeIf {it.isNotEmpty()}?.plus("/128")),peer.getJSONObject("endpoint").getString("v4"),r.map {it.toInt() and 255},emptyMap())
        }
        fun parse(value: String): WarpProfile {
            require(value.length in 1..16384)
            val fields=linkedMapOf<String,String>();var section="";var interfaces=0;var peers=0
            for(raw in value.lineSequence()) {
                val line=raw.substringBefore('#').trim();if(line.isEmpty())continue
                if(line.startsWith('[')) {require(line=="[Interface]"||line=="[Peer]");section=line;if(section=="[Interface]")interfaces++ else peers++;require(interfaces==1 && peers<=1);continue}
                val pair=line.split('=',limit=2);require(pair.size==2 && section.isNotEmpty())
                val name=pair[0].trim();val key="$section.$name";require(!fields.containsKey(key)) {"Повтор параметра WARP"};fields[key]=pair[1].trim()
                val allowed=if(section=="[Interface]")setOf("PrivateKey","Address","DNS","MTU","Reserved")+numericOptions.keys+headerOptions+packetOptions else setOf("PublicKey","Endpoint","AllowedIPs","PersistentKeepalive","Reserved")
                require(name in allowed) {"Параметр не разрешён: $name"}
            }
            require(interfaces==1 && peers==1)
            fun field(s: String,n: String)=fields["[$s].$n"]
            field("Interface","MTU")?.let {require(it.toIntOrNull() in 1000..1440)}
            field("Peer","PersistentKeepalive")?.let {require(it.toIntOrNull() in 0..120)}
            field("Peer","AllowedIPs")?.let {require(it.split(',').map(String::trim).toSet()==setOf("0.0.0.0/0","::/0"))}
            field("Interface","DNS")?.let {require(it.split(',').all {ip->address(ip.trim()+if(ip.contains(':'))"/128" else "/32")})}
            require(field("Interface","Reserved")==null || field("Peer","Reserved")==null)
            val r=(field("Interface","Reserved") ?: field("Peer","Reserved") ?: "0,0,0").split(',').map {it.trim().toIntOrNull() ?: -1}
            val opts=fields.filterKeys {it.startsWith("[Interface].")}.mapKeys {it.key.substringAfter('.')}.filterKeys {it in numericOptions || it in headerOptions || it in packetOptions}
            return create(field("Interface","PrivateKey") ?: "",field("Peer","PublicKey") ?: "",field("Interface","Address").orEmpty().split(',').map(String::trim),field("Peer","Endpoint") ?: "",r,opts)
        }
    }
}

object WarpPolicy {
    val packages=setOf("com.google.android.youtube","com.google.android.youtube.tv","com.instagram.android","com.instagram.lite")
    fun apps(installed: Set<String>): Set<String> = packages.intersect(installed).also {require(it.isNotEmpty()) {"Установите YouTube или Instagram"}}
}
class WarpGeneration {private val value=AtomicLong();fun next()=value.incrementAndGet();fun current(generation: Long)=value.get()==generation}

/** Small RFC4648 codec usable on Android24/25 too; rejects noncanonical/ambiguous keys. */
object WarpEncoding {
    private const val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun decode(value: String): ByteArray {
        require(value.length in 4..1024 && value.length%4==0)
        val out=java.io.ByteArrayOutputStream()
        for(start in value.indices step 4) {
            val chars=value.substring(start,start+4);val last=start+4==value.length
            val padding=chars.takeLastWhile {it=='='}.length;require(padding<=2 && (padding==0 || last))
            val digits=chars.mapIndexed {i,c->if(c=='=' && i>=4-padding)0 else alphabet.indexOf(c).also {require(it>=0)}}
            if(padding==2)require(digits[1] and 15==0);if(padding==1)require(digits[2] and 3==0)
            val bits=(digits[0] shl 18) or (digits[1] shl 12) or (digits[2] shl 6) or digits[3]
            out.write(bits ushr 16);if(padding<2)out.write(bits ushr 8);if(padding<1)out.write(bits)
        }
        return out.toByteArray()
    }
}

package io.github.dovecoteescapee.byedpi.warp

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Manual registration of an own key. Strict TLS; no shared identities or third-party relays. */
class WarpEnrollment {
    private val call=AtomicReference<Call?>()
    @Volatile private var cancelled=false
    fun cancel(){cancelled=true;call.getAndSet(null)?.cancel()}
    private fun check(){if(cancelled || Thread.currentThread().isInterrupted)throw CancellationException()}
    fun enroll(): WarpProfile {
        val client=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35)
        try {
            check();val keys=JSONObject(WarpNative.keys());val stamp=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT).apply {timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
            val profile=WarpRegistration {path,body,token,method->
                check();require(System.nanoTime()<end)
                val request=Request.Builder().url("https://api.cloudflareclient.com$path")
                    .header("User-Agent","WARP for Android").header("CF-Client-Version","a-6.35-4471").header("Accept-Encoding","identity").header("Accept","application/json")
                    .method(method,body.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                token?.let {request.header("Authorization","Bearer $it")}
                val pending=client.newCall(request.build());pending.timeout().timeout((end-System.nanoTime()).coerceAtLeast(1),TimeUnit.NANOSECONDS);call.set(pending)
                try {
                    check();pending.execute().use {response->
                        require(response.code==200) {"Cloudflare registration HTTP ${response.code}"}
                        val input=requireNotNull(response.body).byteStream();val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                        input.use {while(true){check();val n=it.read(buffer);if(n<0)break;require(out.size()+n<=65536);out.write(buffer,0,n)}}
                        check();JSONObject(out.toString("UTF-8"))
                    }
                } finally {call.compareAndSet(pending,null)}
            }.enroll(keys.getString("private"),keys.getString("public"),stamp)
            check();return profile
        } finally {call.getAndSet(null)?.cancel();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
    }
}

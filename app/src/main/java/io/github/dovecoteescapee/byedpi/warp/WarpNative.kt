package io.github.dovecoteescapee.byedpi.warp

import android.net.VpnService

object WarpNative {
    init {check(android.os.Build.VERSION.SDK_INT>=24);System.loadLibrary("warpjni")}
    external fun keys(): String
    /** Takes ownership of a detached duplicate of the Android TUN descriptor, including on failure. */
    external fun start(fd: Int,config: String,reserved: String,handshakeOnly: Boolean,vpn: VpnService): String
    external fun stop()
    external fun stats(): String
}

package io.github.dovecoteescapee.byedpi.core

import io.github.dovecoteescapee.byedpi.utility.MobilePlan

/** One invocation per disposable :mobileengine/:tuner process. No C state is reused. */
class MobileProxy {
    companion object { init { System.loadLibrary("mobilepilot") } }
    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    fun run(command: String,port: Int,onBound: (Boolean)->Unit = {}): Int {
        require(port in MobilePlan.ports)
        val args = MobilePlan.arguments(command,port)
        val fd = jniCreateSocketWithCommandLine(args)
        onBound(fd >= 0)
        check(fd >= 0) { "Native engine initialization failed" }
        return jniStartProxy(fd)
    }
}

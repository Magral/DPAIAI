package io.github.dovecoteescapee.byedpi.core

/** One invocation per disposable :mobileengine/:tuner process. No C state is reused. */
class MobileProxy {
    companion object { init { System.loadLibrary("mobilepilot") } }
    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    fun run(command: String,port: Int,onBound: (Boolean)->Unit = {}): Int {
        require(port == 1080 || port == 19080)
        val args = "--ip 127.0.0.1 --port $port --proto udp --pf 53 --auto none $command".trim().split(' ').toTypedArray()
        val fd = jniCreateSocketWithCommandLine(arrayOf("ciadpi")+args)
        onBound(fd >= 0)
        check(fd >= 0) { "Native engine initialization failed" }
        return jniStartProxy(fd)
    }
}

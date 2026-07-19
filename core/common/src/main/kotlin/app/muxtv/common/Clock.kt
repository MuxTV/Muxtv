package app.muxtv.common

fun interface MuxClock {
    fun epochMillis(): Long
}

object SystemMuxClock : MuxClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
}

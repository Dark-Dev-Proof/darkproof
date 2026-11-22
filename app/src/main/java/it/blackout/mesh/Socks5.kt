package it.blackout.mesh

/**
 * Connections through Tor's SOCKS port with a user name of our choosing: Tor keeps streams with different user names on
 * different circuits, so each group reaches the relays from a different exit (spec "Tor"). Java's own SOCKS can't vary
 * the name per connection. Host names go to Tor, never to the phone's resolver.
 */
object Socks5 {
    /** A made-up address that carries the host name to [Tunnel]. */
    val noDns = object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByAddress(hostname, ByteArray(4))) }

    fun factory(port: Int, user: String): javax.net.SocketFactory = object : javax.net.SocketFactory() {
        override fun createSocket(): java.net.Socket = Tunnel(port, user)
        private fun no(): Nothing = throw UnsupportedOperationException("OkHttp makes unconnected sockets")
        override fun createSocket(h: String?, p: Int): java.net.Socket = no()
        override fun createSocket(h: String?, p: Int, l: java.net.InetAddress?, lp: Int): java.net.Socket = no()
        override fun createSocket(h: java.net.InetAddress?, p: Int): java.net.Socket = no()
        override fun createSocket(h: java.net.InetAddress?, p: Int, l: java.net.InetAddress?, lp: Int): java.net.Socket = no()
    }

    private class Tunnel(private val proxy: Int, private val user: String) : java.net.Socket() {
        override fun connect(to: java.net.SocketAddress, timeout: Int) {
            val a = to as java.net.InetSocketAddress
            super.connect(java.net.InetSocketAddress("127.0.0.1", proxy), timeout)
            val was = soTimeout
            soTimeout = 60_000 // building a circuit can take a while
            handshake(getInputStream(), getOutputStream(), a.hostString, a.port, user)
            soTimeout = was
        }
    }

    /** RFC 1928 with RFC 1929 user and password: asks for a connection to [host]. Throws if it is refused. */
    fun handshake(i: java.io.InputStream, o: java.io.OutputStream, host: String, port: Int, user: String) {
        val data = java.io.DataInputStream(i)
        fun read(n: Int) = ByteArray(n).also(data::readFully)
        o.write(byteArrayOf(5, 1, 2)); o.flush()
        if (!read(2).contentEquals(byteArrayOf(5, 2))) throw java.io.IOException("SOCKS: no user name method")
        val name = user.toByteArray()
        o.write(byteArrayOf(1, name.size.toByte()) + name + byteArrayOf(1, 'x'.code.toByte())); o.flush()
        if (read(2)[1] != 0.toByte()) throw java.io.IOException("SOCKS: user name refused")
        val h = host.toByteArray()
        o.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port ushr 8).toByte(), port.toByte())); o.flush()
        val r = read(4)
        if (r[1] != 0.toByte()) throw java.io.IOException("SOCKS: connection refused (${r[1]})")
        read(when (r[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> read(1)[0].toInt() and 0xFF; else -> throw java.io.IOException("SOCKS: bad address type") } + 2)
    }
}

package com.shilapi.xcertplay.network

import java.net.BindException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Binds the AirPlay control listener, falling back when the preferred port is already taken.
 *
 * Some head units ship a factory CarPlay daemon that permanently listens on the default AirPlay
 * port (7000) on every interface, so binding DiPlay's listener fails with EADDRINUSE. The bound
 * port is advertised to the iPhone through Bonjour and iAP2, so any free port works.
 */
object AirPlayPortSelector {
    /** Ports tried, in order, after the preferred port; an ephemeral port is the last resort. */
    val FALLBACK_PORTS: IntRange = 7001..7010

    /** Specific per-family listeners avoid relying on a platform's IPV6_V6ONLY default. */
    fun bindAll(
        addresses: List<InetAddress>,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServerSocket> {
        require(addresses.isNotEmpty()) { "At least one listener address is required" }
        val candidates = listOf(preferredPort) + fallbackPorts.filter { it != preferredPort } + List(4) { 0 }
        for (candidate in candidates) {
            val servers = mutableListOf<ServerSocket>()
            try {
                for (address in addresses.distinct()) {
                    servers.add(bindPort(address, servers.firstOrNull()?.localPort ?: candidate))
                }
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                if (error is BindException) continue
                throw error
            }
            try {
                val port = servers.first().localPort
                if (preferredPort != 0 && port != preferredPort) onFallback(preferredPort, port)
                return servers
            } catch (error: Throwable) {
                servers.forEach { closeAfterFailure(it, error) }
                throw error
            }
        }
        throw BindException("No common AirPlay port available for the selected interface addresses")
    }

    fun bind(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int> = FALLBACK_PORTS,
        onFallback: (busyPort: Int, boundPort: Int) -> Unit = { _, _ -> },
    ): ServerSocket {
        tryBind(address, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(address, port)?.let { server ->
                return reportFallback(server, preferredPort, onFallback)
            }
        }
        // An ephemeral bind on the specific address distinguishes "every port busy" from "address
        // gone". If even port 0 fails with EADDRNOTAVAIL, the address is no longer on any up
        // interface (the Wi-Fi link is down or mid-flap), so retrying ports on it is pointless —
        // bind the wildcard instead, which always succeeds and still reaches the iPhone via the
        // host address advertised over Bonjour/iAP2.
        try {
            return reportFallback(bindPort(address, 0), preferredPort, onFallback)
        } catch (error: BindException) {
            if (!isAddressUnavailable(error)) throw error
        }
        val wildcard = bindWildcard(address, preferredPort, fallbackPorts)
        return if (wildcard.localPort != preferredPort) {
            reportFallback(wildcard, preferredPort, onFallback)
        } else {
            wildcard
        }
    }

    private fun bindWildcard(
        address: InetAddress,
        preferredPort: Int,
        fallbackPorts: Iterable<Int>,
    ): ServerSocket {
        val wildcard = if (address is Inet6Address) {
            InetAddress.getByName("::")
        } else {
            InetAddress.getByName("0.0.0.0")
        }
        tryBind(wildcard, preferredPort)?.let { return it }
        for (port in fallbackPorts) {
            if (port == preferredPort) continue
            tryBind(wildcard, port)?.let { return it }
        }
        return bindPort(wildcard, 0)
    }

    /** True when the address itself cannot be assigned (EADDRNOTAVAIL), not merely the port busy. */
    private fun isAddressUnavailable(error: BindException): Boolean {
        val message = error.message ?: return false
        return message.contains("EADDRNOTAVAIL") ||
            message.contains("Cannot assign requested address")
    }

    private fun tryBind(address: InetAddress, port: Int): ServerSocket? = try {
        bindPort(address, port)
    } catch (_: BindException) {
        null
    }

    private fun bindPort(address: InetAddress, port: Int): ServerSocket {
        val server = ServerSocket()
        return try {
            server.bind(InetSocketAddress(address, port))
            server
        } catch (error: Throwable) {
            closeAfterFailure(server, error)
            throw error
        }
    }

    private fun reportFallback(
        server: ServerSocket,
        preferredPort: Int,
        onFallback: (Int, Int) -> Unit,
    ): ServerSocket = try {
        onFallback(preferredPort, server.localPort)
        server
    } catch (error: Throwable) {
        // Ownership transfers to the caller only after notification succeeds.
        closeAfterFailure(server, error)
        throw error
    }

    private fun closeAfterFailure(server: ServerSocket, error: Throwable) {
        try {
            server.close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
    }
}

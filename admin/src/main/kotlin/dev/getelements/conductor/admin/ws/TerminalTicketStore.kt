package dev.getelements.conductor.admin.ws

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived, single-use tickets that authorize opening a terminal WebSocket connection. Browsers
 * can't set an `Authorization` header on the native `WebSocket()` constructor, so the dashboard first
 * mints a ticket over an authenticated REST call ([dev.getelements.conductor.admin.ConductorAdminJobsResource]),
 * then passes it as a `?ticket=` query parameter on the WebSocket handshake, where it's validated and
 * consumed exactly once — mirroring the bearer-token-at-handshake pattern `stdio-bridge` uses for its
 * own WebSocket endpoints.
 */
internal object TerminalTicketStore {

    private val TTL: Duration = Duration.ofSeconds(30)

    private data class Ticket(val jobId: String, val containerId: String?, val expiresAt: Instant)

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()

    /**
     * Mints a new ticket authorizing a single connection to [jobId] / [containerId] (`null` for the
     * primary container).
     */
    fun mint(jobId: String, containerId: String?): String {
        purgeExpired()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[token] = Ticket(jobId, containerId, Instant.now().plus(TTL))
        return token
    }

    /**
     * Validates and consumes (single-use) [token] for the given [jobId] / [containerId]. Returns
     * `true` if the ticket existed, was unexpired, and matched; `false` otherwise. Consumes the
     * ticket either way if it existed, so it can never be replayed.
     */
    fun consume(token: String, jobId: String, containerId: String?): Boolean {
        val ticket = tickets.remove(token) ?: return false
        return Instant.now().isBefore(ticket.expiresAt) && ticket.jobId == jobId && ticket.containerId == containerId
    }

    private fun purgeExpired() {
        val now = Instant.now()
        tickets.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

}

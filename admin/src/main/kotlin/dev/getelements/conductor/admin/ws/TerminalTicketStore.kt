package dev.getelements.conductor.admin.ws

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.bson.Document
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Short-lived, single-use tickets that authorize opening a terminal WebSocket connection. Browsers
 * can't set an `Authorization` header on the native `WebSocket()` constructor, so the dashboard first
 * mints a ticket over an authenticated REST call ([dev.getelements.conductor.admin.ConductorAdminJobsResource]),
 * then passes it as a `?ticket=` query parameter on the WebSocket handshake, where it's validated and
 * consumed exactly once — mirroring the bearer-token-at-handshake pattern `stdio-bridge` uses for its
 * own WebSocket endpoints.
 *
 * Backed by the platform's shared MongoDB (the same database `SessionDao` uses) rather than an
 * in-JVM map: `admin` can run as more than one instance (e.g. mid-rolling-deploy), and a ticket
 * minted by the REST call on one instance must be consumable by whichever instance the immediately
 * following WebSocket upgrade happens to land on. An in-memory store made every such window produce
 * "invalid or expired ticket" on effectively every attempt (see #31).
 */
@Singleton
class TerminalTicketStore @Inject constructor(database: MongoDatabase) {

    data class Ticket(val jobId: String, val containerId: String?, val command: List<String>?)

    companion object {
        private val TTL: Duration = Duration.ofSeconds(30)
        private const val COLLECTION_NAME = "conductor_admin_terminal_tickets"
    }

    private val random = SecureRandom()

    private val collection = database.getCollection(COLLECTION_NAME).also {
        // Garbage-collects abandoned (never-consumed) tickets; correctness of expiry itself is
        // enforced explicitly in consume()'s query below, since the TTL background sweep only runs
        // on its own ~60s cadence and can't be relied on for point-in-time expiry checks.
        it.createIndex(Indexes.ascending("expiresAt"), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
    }

    /**
     * Mints a new ticket authorizing a single connection to [jobId] / [containerId] (`null` for the
     * primary container), optionally exec-ing [command] instead of attaching to the container's own
     * process (`null` uses the provider's default).
     */
    fun mint(jobId: String, containerId: String?, command: List<String>? = null): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val document = Document("_id", token)
            .append("jobId", jobId)
            .append("containerId", containerId)
            .append("command", command)
            .append("expiresAt", Date.from(Instant.now().plus(TTL)))
        collection.insertOne(document)
        return token
    }

    /**
     * Validates and consumes (single-use) [token] for the given [jobId] / [containerId]. Returns the
     * matched [Ticket] (so the caller can read its [Ticket.command]) if it existed, was unexpired, and
     * matched; `null` otherwise. Consumes the ticket either way if it existed, so it can never be
     * replayed — `findOneAndDelete` is atomic, so concurrent consume attempts for the same token can
     * never both succeed.
     */
    fun consume(token: String, jobId: String, containerId: String?): Ticket? {
        val filter = Filters.and(
            Filters.eq("_id", token),
            Filters.eq("jobId", jobId),
            Filters.eq("containerId", containerId),
            Filters.gt("expiresAt", Date.from(Instant.now()))
        )
        val document = collection.findOneAndDelete(filter) ?: return null
        @Suppress("UNCHECKED_CAST")
        val command = document.get("command") as? List<String>
        return Ticket(jobId, containerId, command)
    }

}

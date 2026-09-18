package dev.getelements.conductor.admin

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import dev.getelements.elements.sdk.jakarta.rs.DefaultExceptionMapper
import jakarta.ws.rs.core.Application

@ElementServiceImplementation
@ElementServiceExport(Application::class)
class ConductorAdminApplication : Application() {   

    companion object {
        @JvmField
        @ElementDefaultAttribute(value = "true")
        val AUTH_ENABLED: String = "dev.getelements.elements.auth.enabled"

        /**
         * Mounts the terminal WebSocket endpoints ([dev.getelements.conductor.admin.ws.PrimaryContainerTerminalEndpoint],
         * [dev.getelements.conductor.admin.ws.ContainerTerminalEndpoint]): `ws://<host>/conductor/ws/service/{jobId}[/{containerId}]`.
         *
         * A 1.2-only feature with no prior release's contract to preserve, so it gets its own
         * top-level segment rather than nesting under [RS_ROOT]'s `/conductor/admin` — the REST and
         * WebSocket loaders (`JakartaRsLoader`/`JakartaWebsocketLoader`) each register their context
         * path in a shared `HttpPathRegistry`, and having both claim the exact same path for one
         * Element is suspected to cause the WebSocket loader's endpoint scan to silently find nothing
         * (see https://github.com/NamazuStudios/elements/issues/95). Keep this and [RS_ROOT]
         * non-overlapping. Must stay in sync with the client-side copy in `admin/ui/superuser/api.ts`.
         */
        @JvmField
        @ElementDefaultAttribute(value = "/conductor/ws")
        val WS_ROOT: String = "dev.getelements.elements.element.ws.root"

        /**
         * Mounts the REST API ([ConductorAdminResource], [ConductorAdminJobsResource]):
         * `http://<host>/conductor/admin/...`.
         *
         * This is the original path from the 1.1 release line, restored for backward compatibility
         * with existing integrations after two unnecessary moves in the 1.2.x line (`/conductor/admin`
         * → `/conductor/admin/rest` → `/conductor/admin-console/rest`, the latter landing in a patch
         * release). An operator who sets `http.path.prefix=/conductor` will collide with the
         * platform's own reserved `{httpPathPrefix}/admin` system path (see
         * https://github.com/NamazuStudios/namazu-conductor/issues/24) — that risk is knowingly
         * accepted here in favor of compatibility; such an operator can override the
         * `dev.getelements.elements.element.rs.root` attribute directly. Must stay in sync with the
         * client-side copy in `admin/ui/superuser/api.ts`.
         */
        @JvmField
        @ElementDefaultAttribute(value = "/conductor/admin")
        val RS_ROOT: String = "dev.getelements.elements.element.rs.root"

    }

    override fun getClasses(): Set<Class<*>> = setOf(
        ConductorAdminResource::class.java,
        ConductorAdminJobsResource::class.java,
        DefaultExceptionMapper::class.java
    )

    override fun getSingletons(): Set<Any> = setOf(ConductorAdminJacksonProvider())

}
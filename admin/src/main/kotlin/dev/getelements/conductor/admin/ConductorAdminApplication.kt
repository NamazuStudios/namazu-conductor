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
         * [dev.getelements.conductor.admin.ws.ContainerTerminalEndpoint]): `ws://<host>/conductor/admin/ws/service/{jobId}[/{containerId}]`.
         *
         * Deliberately a **different** context path from [RS_ROOT] rather than sharing one — the REST
         * and WebSocket loaders (`JakartaRsLoader`/`JakartaWebsocketLoader`) each register their context
         * path in a shared `HttpPathRegistry`, and having both claim the exact same path for one Element
         * is suspected to cause the WebSocket loader's endpoint scan to silently find nothing (see
         * https://github.com/NamazuStudios/elements/issues/95). Keep this and [RS_ROOT] non-overlapping.
         */
        @JvmField
        @ElementDefaultAttribute(value = "/conductor/admin/ws")
        val WS_ROOT: String = "dev.getelements.elements.element.ws.root"

        /**
         * Mounts the REST API ([ConductorAdminResource], [ConductorAdminJobsResource]):
         * `http://<host>/conductor/admin/rest/...`.
         *
         * Deliberately a **different** context path from [WS_ROOT] — see that field's doc for why.
         */
        @JvmField
        @ElementDefaultAttribute(value = "/conductor/admin/rest")
        val RS_ROOT: String = "dev.getelements.elements.element.rs.root"

        /**
         * Serves the dashboard UI plugin bundle: `http://<host>/conductor/admin/ui/...`.
         *
         * Without this override, the UI loader falls back to the element's raw package name
         * (`dev.getelements.conductor.admin`) as the path segment in the default `/app/ui/{prefix}`
         * scheme, which collides with a reserved system API path in production and gets silently
         * refused ("Static content path '/app/ui/dev.getelements.conductor.admin' is inside a
         * reserved system API path"). Deliberately not `APPLICATION_PREFIX`, which would also override
         * [WS_ROOT]/[RS_ROOT]'s defaults and reintroduce the collision described there.
         */
        @JvmField
        @ElementDefaultAttribute(value = "/conductor/admin/ui")
        val UI_CONTENT_URI: String = "dev.getelements.element.ui.uri"

    }

    override fun getClasses(): Set<Class<*>> = setOf(
        ConductorAdminResource::class.java,
        ConductorAdminJobsResource::class.java,
        DefaultExceptionMapper::class.java
    )

    override fun getSingletons(): Set<Any> = setOf(ConductorAdminJacksonProvider())

}
package dev.getelements.conductor.admin

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import jakarta.ws.rs.core.Application

@ElementServiceImplementation
@ElementServiceExport(Application::class)
class ConductorAdminApplication : Application() {   

    companion object {
        @JvmField
        @ElementDefaultAttribute(value = "true")
        val AUTH_ENABLED: String = "dev.getelements.elements.auth.enabled"

        @JvmField
        @ElementDefaultAttribute(value = "/conductor/admin")
        val RS_ROOT: String = "dev.getelements.elements.element.rs.root"

    }

    override fun getClasses(): Set<Class<*>> = setOf(
        ConductorAdminResource::class.java,
        ConductorAdminJobsResource::class.java
    )

}
package dev.getelements.conductor.admin.guice

import com.google.inject.PrivateModule
import com.google.inject.Singleton
import dev.getelements.conductor.admin.ws.TerminalTicketStore

class ConductorAdminModule : PrivateModule() {
    override fun configure() {
        // REST endpoints are auto-discovered by the Elements JAX-RS runtime via
        // @ElementServiceExport(Application::class) on ConductorAdminApplication.

        // Exposed so TerminalSessionHandler (instantiated by the JSR-356 container, not Guice) can
        // reach the same instance via ElementSupplier.getElementLocal(...).serviceLocator.getInstance(...)
        // that ConductorAdminJobsResource gets through ordinary constructor injection.
        bind(TerminalTicketStore::class.java).`in`(Singleton::class.java)
        expose(TerminalTicketStore::class.java)
    }
}
package dev.getelements.conductor.admin.guice

import com.google.inject.PrivateModule

class ConductorAdminModule : PrivateModule() {
    override fun configure() {
        // REST endpoints are auto-discovered by the Elements JAX-RS runtime via
        // @ElementServiceExport(Application::class) on ConductorAdminApplication.
        // No bindings need to be exposed here.
    }
}
package dev.getelements.conductor.multiplay.guice

import com.google.inject.PrivateModule
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.conductor.multiplay.service.MultiplayOrchestrationService

class MultiplayOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java).to(MultiplayOrchestrationService::class.java)
        expose(OrchestrationService::class.java)
    }

}

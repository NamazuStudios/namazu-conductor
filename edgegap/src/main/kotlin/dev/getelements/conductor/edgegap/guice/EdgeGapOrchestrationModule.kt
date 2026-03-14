package dev.getelements.conductor.edgegap.guice

import com.google.inject.PrivateModule
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService

class EdgeGapOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java).to(EdgeGapOrchestrationService::class.java)
        expose(OrchestrationService::class.java)
    }

}

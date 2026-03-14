package dev.getelements.conductor.fargate.guice

import com.google.inject.PrivateModule
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.conductor.fargate.service.FargateOrchestrationService

class FargateOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java).to(FargateOrchestrationService::class.java)
        expose(OrchestrationService::class.java)
    }

}

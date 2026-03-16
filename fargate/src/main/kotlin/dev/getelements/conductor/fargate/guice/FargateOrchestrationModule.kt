package dev.getelements.conductor.fargate.guice

import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.fargate.FargateAttributes
import dev.getelements.conductor.fargate.service.FargateOrchestrationService
import dev.getelements.conductor.service.OrchestrationService
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ecs.EcsClient

class FargateOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java)
            .to(FargateOrchestrationService::class.java)
            .`in`(Singleton::class.java)
        expose(OrchestrationService::class.java)
    }

    @Provides
    @Singleton
    fun provideEcsClient(@Named(FargateAttributes.REGION) region: String): EcsClient =
        EcsClient.builder()
            .region(Region.of(region))
            .build()

}
package dev.getelements.conductor.ecs.guice

import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.ecs.EcsAttributes
import dev.getelements.conductor.ecs.service.EcsOrchestrationService
import dev.getelements.conductor.service.OrchestrationService
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EcsOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java)
            .to(EcsOrchestrationService::class.java)
            .`in`(Singleton::class.java)
        expose(OrchestrationService::class.java)
    }

    @Provides
    @Singleton
    fun provideEcsClient(@Named(EcsAttributes.REGION) region: String): EcsClient =
        EcsClient.builder()
            .region(Region.of(region))
            .build()

    @Provides
    @Singleton
    fun provideEc2Client(@Named(EcsAttributes.REGION) region: String): Ec2Client =
        Ec2Client.builder()
            .region(Region.of(region))
            .build()

    @Provides
    @Singleton
    fun provideExecutorService(): ExecutorService = Executors.newCachedThreadPool()

}
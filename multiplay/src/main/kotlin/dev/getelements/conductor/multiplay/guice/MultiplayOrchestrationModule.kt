package dev.getelements.conductor.multiplay.guice

import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import dev.getelements.conductor.multiplay.service.MultiplayOrchestrationService
import dev.getelements.conductor.service.OrchestrationService
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder

class MultiplayOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java)
            .to(MultiplayOrchestrationService::class.java)
            .`in`(Singleton::class.java)
        expose(OrchestrationService::class.java)
    }

    @Provides
    @Singleton
    fun provideClient(): Client = ClientBuilder.newBuilder()
        .register(JacksonJsonProvider::class.java)
        .build()

}
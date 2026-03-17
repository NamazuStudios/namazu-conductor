package dev.getelements.conductor.edgegap.guice

import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService
import dev.getelements.conductor.service.OrchestrationService
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Guice [PrivateModule] that wires the EdgeGap [OrchestrationService] implementation.
 *
 * Binds a singleton [Client] configured with Jackson JSON support and exposes only
 * [OrchestrationService] to the parent injector. All EdgeGap-specific bindings
 * (the [Client], configuration strings) remain private.
 */
class EdgeGapOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(OrchestrationService::class.java)
            .to(EdgeGapOrchestrationService::class.java)
            .`in`(Singleton::class.java)

        expose(OrchestrationService::class.java)
    }

    @Provides
    @Singleton
    fun provideClient(): Client = ClientBuilder.newBuilder()
        .register(JacksonJsonProvider::class.java)
        .build()

    @Provides
    @Singleton
    fun provideExecutorService(): ExecutorService = Executors.newCachedThreadPool()

}

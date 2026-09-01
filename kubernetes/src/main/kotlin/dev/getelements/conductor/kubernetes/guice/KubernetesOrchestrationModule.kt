package dev.getelements.conductor.kubernetes.guice

import com.google.inject.PrivateModule
import com.google.inject.Provides
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.kubernetes.KubernetesAttributes
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService
import dev.getelements.conductor.service.DaemonOrchestrationService
import dev.getelements.conductor.service.OrchestrationService
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Guice [PrivateModule] that wires the Kubernetes [OrchestrationService] and
 * [DaemonOrchestrationService] implementations — a single shared [KubernetesOrchestrationService]
 * instance backs both.
 *
 * Provides a singleton [KubernetesClient] built from the configured kubeconfig path / master URL
 * (falling back to Fabric8 auto-detection of in-cluster or `~/.kube/config` configuration) and an
 * [ExecutorService] for background status polling. Only [OrchestrationService] and
 * [DaemonOrchestrationService] are exposed to the parent injector; all Kubernetes-specific bindings
 * remain private.
 */
class KubernetesOrchestrationModule : PrivateModule() {

    override fun configure() {
        bind(KubernetesOrchestrationService::class.java).`in`(Singleton::class.java)
        bind(OrchestrationService::class.java).to(KubernetesOrchestrationService::class.java)
        bind(DaemonOrchestrationService::class.java).to(KubernetesOrchestrationService::class.java)
        expose(OrchestrationService::class.java)
        expose(DaemonOrchestrationService::class.java)
    }

    @Provides
    @Singleton
    fun provideKubernetesClient(
        @Named(KubernetesAttributes.KUBECONFIG_PATH) kubeconfigPath: String,
        @Named(KubernetesAttributes.MASTER_URL) masterUrl: String
    ): KubernetesClient {
        val config: Config = if (kubeconfigPath.isNotBlank()) {
            Config.fromKubeconfig(File(kubeconfigPath).readText())
        } else {
            Config.autoConfigure(null)
        }

        if (masterUrl.isNotBlank()) {
            config.masterUrl = masterUrl
        }

        return KubernetesClientBuilder().withConfig(config).build()
    }

    @Provides
    @Singleton
    fun provideExecutorService(): ExecutorService = Executors.newCachedThreadPool()

}
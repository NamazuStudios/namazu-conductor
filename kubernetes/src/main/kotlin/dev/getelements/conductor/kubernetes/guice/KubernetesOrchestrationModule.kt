package dev.getelements.conductor.kubernetes.guice

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
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
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
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

        return KubernetesClientBuilder()
            .withConfig(config)
            .withKubernetesSerialization(UnknownFieldTolerantKubernetesSerialization())
            .build()
    }

    @Provides
    @Singleton
    fun provideExecutorService(): ExecutorService = Executors.newCachedThreadPool()

}

/**
 * [KubernetesSerialization] that ignores JSON fields the client's model classes don't know about.
 *
 * The Kubernetes API server legitimately returns fields no client model version knows — newer API
 * versions add fields, and `managedFields` entries carry server-managed `FieldsV1` content whose
 * keys are arbitrary (`f:metadata`, `f:template`, ...). Fabric8's [io.fabric8.kubernetes.model.jackson.UnmatchedFieldTypeModule]
 * normally absorbs such fields into each model's `additionalProperties`, but that absorb path
 * depends on Jackson any-setter visibility, which does not survive every Element classloader
 * configuration: in the Namazu Cloud control plane (conductor.kubernetes + several sibling
 * elements sharing an API classloader, with Jackson also present on the platform classpath) the
 * FieldsV1 deserializer ends up with no any-setter and Jackson's default
 * [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES] turns a routine PodTemplate listing into
 * `UnrecognizedPropertyException: Unrecognized field "f:metadata"`.
 *
 * Ignoring unknown fields is the standard Kubernetes client behavior (kubectl drops them
 * client-side), and it is robust against any classloader arrangement: `handleUnknownVanilla`
 * returns silently instead of throwing, for every model class and every field.
 */
private class UnknownFieldTolerantKubernetesSerialization : KubernetesSerialization() {

    override fun configureMapper(mapper: ObjectMapper) {
        super.configureMapper(mapper)
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

}
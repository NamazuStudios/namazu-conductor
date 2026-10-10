package dev.getelements.conductor.kubernetes.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import com.google.inject.name.Named
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.kubernetes.KubernetesAttributes
import dev.getelements.conductor.service.SecretStore
import dev.getelements.conductor.service.SecretSummary
import dev.getelements.conductor.service.StoredSecret
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Resource
import java.util.Base64

/**
 * [SecretStore] backed by Kubernetes `Secret`s in the provider's configured namespace
 * ([KubernetesAttributes.NAMESPACE]). Conductor-managed secrets are labelled
 * [LABEL_STORED_SECRET] so [list] never surfaces unrelated secrets that happen to live in the
 * same namespace, and each secret's caller-supplied metadata rides in a single JSON annotation
 * ([ANN_SECRET_METADATA]) — annotation keys must be DNS-safe, so arbitrary caller metadata keys
 * can't be mapped onto them directly.
 *
 * Secret values are created via `stringData` (the API server stores them base64-encoded in
 * `data`); [recall] decodes. Values are never returned by [list] — the store's contract.
 */
class KubernetesSecretStore @Inject constructor(
    private val client: KubernetesClient,
    @Named(KubernetesAttributes.NAMESPACE) private val namespace: String,
) : SecretStore {

    private val objectMapper = ObjectMapper()

    override fun store(name: String, values: Map<String, String>, metadata: Map<String, String>) {
        requireValidName(name)
        val builder = SecretBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(LABEL_STORED_SECRET, "true")
                .addToAnnotations(ANN_SECRET_METADATA, objectMapper.writeValueAsString(metadata))
            .endMetadata()
        values.forEach { (key, value) -> builder.addToStringData(key, value) }
        client.secrets().inNamespace(namespace).resource(builder.build()).createOrReplace()
    }

    override fun list(): List<SecretSummary> =
        client.secrets().inNamespace(namespace).withLabel(LABEL_STORED_SECRET, "true").list().items
            .mapNotNull { secret ->
                val name = secret.metadata?.name ?: return@mapNotNull null
                SecretSummary(name = name, metadata = metadataOf(secret))
            }
            .sortedBy { it.name }

    override fun recall(name: String): StoredSecret? {
        requireValidName(name)
        val secret = resourceFor(name).get() ?: return null
        return StoredSecret(
            name = name,
            values = secret.data?.mapValues { (_, value) ->
                String(Base64.getDecoder().decode(value))
            } ?: emptyMap(),
            metadata = metadataOf(secret)
        )
    }

    override fun delete(name: String) {
        requireValidName(name)
        resourceFor(name).delete()
    }

    private fun resourceFor(name: String): Resource<Secret> =
        client.secrets().inNamespace(namespace).withName(name)

    private fun metadataOf(secret: Secret): Map<String, String> =
        secret.metadata?.annotations?.get(ANN_SECRET_METADATA)?.let { json ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                objectMapper.readValue(json, Map::class.java) as Map<String, String>
            }.getOrElse {
                throw JobException(
                    "Stored secret '${secret.metadata?.name}' carries unreadable metadata", it
                )
            }
        } ?: emptyMap()

    private fun requireValidName(name: String) {
        if (name.length > MAX_NAME_LENGTH || !NAME_PATTERN.matches(name)) {
            throw JobException(
                "'$name' is not a valid secret name: must be a DNS-1123 subdomain (lowercase " +
                    "alphanumeric, '-' and '.', starting and ending alphanumeric) of at most " +
                    "$MAX_NAME_LENGTH characters"
            )
        }
    }

    companion object {

        /** Marker label — conductor-managed secrets only; [list] never surfaces anything else. */
        const val LABEL_STORED_SECRET = "namazu.conductor/secret"

        /** Single-annotation home for the store's caller-supplied metadata (JSON-serialized). */
        const val ANN_SECRET_METADATA = "namazu.conductor/secret-metadata"

        private const val MAX_NAME_LENGTH = 253

        /** Kubernetes DNS-1123 subdomain names — what a `Secret` object's metadata.name accepts. */
        private val NAME_PATTERN = Regex(
            "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$"
        )

    }

}

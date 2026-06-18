import dev.getelements.elements.sdk.local.ElementsLocalBuilder
import java.io.File
import java.util.Properties

/**
 * Runs your local Element in the SDK.
 */
fun main() {

    ProcessBuilder("docker", "compose", "up", "-d")
        .directory(File("services-dev"))
        .inheritIO()
        .start()
        .waitFor()

    val local = ElementsLocalBuilder.getDefault()
        .withSourceRoot()
        .withDeployment { builder ->
            builder.useDefaultRepositories(true)

            loadProperties("ecs.properties")?.let { props ->
                builder
                    .elementPackage()
                    .pathAttributes(mapOf("dev.getelements.conductor.ecs" to props))
                    .elmArtifact("dev.getelements.conductor:ecs:elm:1.0.9-SNAPSHOT")
                    .endElementPackage()
            }

            loadProperties("edgegap.properties")?.let { props ->
                builder
                    .elementPackage()
                    .pathAttributes(mapOf("dev.getelements.conductor.edgegap" to props))
                    .elmArtifact("dev.getelements.conductor:edgegap:elm:1.0.9-SNAPSHOT")
                    .endElementPackage()
            }

            loadProperties("kubernetes.properties")?.let { props ->
                builder
                    .elementPackage()
                    .pathAttributes(mapOf("dev.getelements.conductor.kubernetes" to props))
                    .elmArtifact("dev.getelements.conductor:kubernetes:elm:1.0.9-SNAPSHOT")
                    .endElementPackage()
            }

            builder
                .elementPackage()
                .elmArtifact("dev.getelements.conductor:admin:elm:1.0.9-SNAPSHOT")
                .endElementPackage()

            builder.build()
        }
        .build()

    local.start()
    local.run()

}

/**
 * Loads a properties file from the working directory. Returns null if the file does not exist,
 * allowing the caller to skip loading the associated Element.
 */
fun loadProperties(filename: String): Map<String, String>? {
    val file = File(filename)
    if (!file.exists()) return null
    val props = Properties()
    file.inputStream().use { props.load(it) }
    @Suppress("UNCHECKED_CAST")
    return props as Map<String, String>
}

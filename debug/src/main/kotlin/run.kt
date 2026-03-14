import dev.getelements.elements.sdk.local.ElementsLocalBuilder
import java.io.File

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
            builder
                .useDefaultRepositories(true)
                .elementPath()
                    .addSpiBuiltin("DEFAULT")
                    .addApiArtifact("dev.getelements.conductor:api:1.0-SNAPSHOT")
                    .addElementArtifact("dev.getelements.conductor:element:1.0-SNAPSHOT")
                .endElementPath()
                .build()
        }
        .build()

    local.start()
    local.run()

}

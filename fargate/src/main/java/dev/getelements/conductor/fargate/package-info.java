@ElementDefinition(recursive = true)
@GuiceElementModule(FargateOrchestrationModule.class)
package dev.getelements.conductor.fargate;

import dev.getelements.conductor.fargate.guice.FargateOrchestrationModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;

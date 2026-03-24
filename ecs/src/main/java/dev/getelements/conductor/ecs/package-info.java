@ElementDefinition(recursive = true)
@GuiceElementModule(EcsOrchestrationModule.class)
package dev.getelements.conductor.ecs;

import dev.getelements.conductor.ecs.guice.EcsOrchestrationModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
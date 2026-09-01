@ElementDefinition(recursive = true)
@GuiceElementModule(EcsOrchestrationModule.class)
@ElementService(OrchestrationService.class)
@ElementService(DaemonOrchestrationService.class)
package dev.getelements.conductor.ecs;

import dev.getelements.conductor.ecs.guice.EcsOrchestrationModule;
import dev.getelements.conductor.service.DaemonOrchestrationService;
import dev.getelements.conductor.service.OrchestrationService;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;

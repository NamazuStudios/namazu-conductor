@ElementDefinition(recursive = true)
@GuiceElementModule(MultiplayOrchestrationModule.class)
@ElementService(OrchestrationService.class)
package dev.getelements.conductor.multiplay;

import dev.getelements.conductor.multiplay.guice.MultiplayOrchestrationModule;
import dev.getelements.conductor.service.OrchestrationService;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
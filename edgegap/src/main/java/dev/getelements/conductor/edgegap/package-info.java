@ElementDefinition(recursive = true)
@GuiceElementModule(EdgeGapOrchestrationModule.class)
@ElementService(OrchestrationService.class)
package dev.getelements.conductor.edgegap;

import dev.getelements.conductor.edgegap.guice.EdgeGapOrchestrationModule;
import dev.getelements.conductor.service.OrchestrationService;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
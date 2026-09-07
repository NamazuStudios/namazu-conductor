@ElementDefinition(recursive = true)
@GuiceElementModule(KubernetesOrchestrationModule.class)
@ElementService(OrchestrationService.class)
@ElementService(DaemonOrchestrationService.class)
package dev.getelements.conductor.kubernetes;

import dev.getelements.conductor.kubernetes.guice.KubernetesOrchestrationModule;
import dev.getelements.conductor.service.DaemonOrchestrationService;
import dev.getelements.conductor.service.OrchestrationService;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
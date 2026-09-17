@ElementDefinition(recursive = true)
@GuiceElementModule(KubernetesOrchestrationModule.class)
@ElementService(OrchestrationService.class)
@ElementService(DaemonOrchestrationService.class)
@ElementPackageRequest("java.net.http")
package dev.getelements.conductor.kubernetes;

import dev.getelements.conductor.kubernetes.guice.KubernetesOrchestrationModule;
import dev.getelements.conductor.service.DaemonOrchestrationService;
import dev.getelements.conductor.service.OrchestrationService;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementPackageRequest;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
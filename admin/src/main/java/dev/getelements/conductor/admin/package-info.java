@ElementDefinition(recursive = true)
@GuiceElementModule(ConductorAdminModule.class)
@ElementDependency("dev.getelements.elements.sdk.service")
@ElementDependency("dev.getelements.elements.sdk.mongo")
package dev.getelements.conductor.admin;

import dev.getelements.conductor.admin.guice.ConductorAdminModule;
import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;

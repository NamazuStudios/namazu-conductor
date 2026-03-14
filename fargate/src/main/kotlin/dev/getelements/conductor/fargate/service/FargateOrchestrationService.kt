package dev.getelements.conductor.fargate.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.user.UserService
import jakarta.inject.Inject

class FargateOrchestrationService : OrchestrationService {

    override fun getAvailableProfiles(): List<JobProfile> {
        TODO("Call EdgeGap API To Application Versions Versions")
    }

    override fun execute(request: JobRequest): JobExecution {
        TODO("Not yet implemented")
    }

}

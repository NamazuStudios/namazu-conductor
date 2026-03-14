package dev.getelements.conductor.multiplay.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService

class MultiplayOrchestrationService : OrchestrationService {

    override fun getAvailableProfiles(): List<JobProfile> {
        TODO("Call EdgeGap API To Application Versions Versions")
    }

    override fun execute(request: JobRequest): JobExecution {
        TODO("Not yet implemented")
    }

}

package dev.getelements.conductor.edgegap.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService

class EdgeGapOrchestrationService : OrchestrationService {

    override fun getAvailableProfiles(): List<JobProfile> {
        TODO("Call EdgeGap API To Application Versions Versions")
    }

    override fun execute(request: JobRequest): JobExecution {
        TODO("Not yet implemented")
    }

}

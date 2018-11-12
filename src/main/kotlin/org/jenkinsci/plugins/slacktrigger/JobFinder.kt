package org.jenkinsci.plugins.slacktrigger

import arrow.core.Either
import hudson.model.Item
import hudson.model.Job
import jenkins.model.Jenkins

internal class JobFinder(private val jenkins: Jenkins) {

    fun findJob(jobSearchText: String): Either<JobSearchError, Job<*, *>> {
        // Find jobs which match the given text
        val jobs = findMatchingJobs(jobSearchText)
        if (jobs.isEmpty()) {
            return Either.left(NoMatchingJobs)
        }

        // Multiple jobs with the same short name were found
        if (jobs.size > 1) {
            return Either.left(MultipleMatchingJobs(jobs))
        }

        // Check whether the job can be built
        val job = jobs.first()
        if (!job.isBuildable()) {
            return Either.left(JobNotBuildable(job))
        }

        // Check whether the user is allowed to build this job
        if (!job.hasPermission(Job.BUILD)) {
            return Either.left(JobBuildNotPermissible(job))
        }

        return Either.right(job)
    }

    private fun findMatchingJobs(jobName: String): List<Job<*, *>> {
        return jenkins.getAllItems(Job::class.java)
                .asSequence()
                .filter { it.hasPermission(Item.READ) }
                .filter { jobName == it.getName() || jobName == it.getFullName() }
                .toList()
    }

}

internal sealed class JobSearchError
internal object NoMatchingJobs : JobSearchError()
internal class MultipleMatchingJobs(val jobs: List<Job<*, *>>) : JobSearchError()
internal class JobNotBuildable(val job: Job<*, *>) : JobSearchError()
internal class JobBuildNotPermissible(val job: Job<*, *>) : JobSearchError()


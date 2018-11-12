package org.jenkinsci.plugins.slacktrigger

import arrow.core.Either
import com.nhaarman.mockito_kotlin.whenever
import hudson.model.Item
import hudson.model.Job
import jenkins.model.Jenkins
import org.amshove.kluent.mock
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldEqual
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class JobFinderTest {

    @Mock
    private lateinit var jenkins: Jenkins

    private lateinit var jobFinder: JobFinder

    private var Jenkins.jobs: List<Job<*, *>>?
        get() = null
        set(value) {
            whenever(jenkins.getAllItems(Job::class.java)).thenReturn(value)
        }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        jobFinder = JobFinder(jenkins)
    }

    @Test
    fun `no match found when there are no jobs`() {
        // Given that no jobs exist
        jenkins.jobs = emptyList()

        // When we search for a job
        val result = jobFinder.findJob("whatever")

        // Then we don't get any matching jobs
        result.isLeft() shouldBe true
        result.left shouldBe NoMatchingJobs
    }

    @Test
    fun `no match found when no names match`() {
        // Given that a couple of jobs exist
        jenkins.jobs = listOf(job("one"), job("two"))

        // When we search for a job with another name
        val result = jobFinder.findJob("three")

        // Then we don't get any matching jobs
        result.isLeft() shouldBe true
        result.left shouldBe NoMatchingJobs
    }


    @Test
    fun `a job is found when exactly one job name matches by name`() {
        // Given that a couple of jobs exist
        val jobOne = job("one")
        val jobTwo = job("two")
        jenkins.jobs = listOf(jobOne, jobTwo)

        // When we search for a job with one of those names
        val result = jobFinder.findJob("one")

        // Then we get a match
        result.isRight() shouldBe true
        result.right shouldBe jobOne
    }

    @Test
    fun `a job is found when exactly one job matches by full name`() {
        // Given that a couple of jobs exist
        val jobOne = job(name = "one", fullName = "folder/one")
        val jobTwo = job(name = "two", fullName = "folder/two")
        jenkins.jobs = listOf(jobOne, jobTwo)

        // When we search for a job by full name
        val result = jobFinder.findJob("folder/two")

        // Then we get a match
        result.isRight() shouldBe true
        result.right shouldBe jobTwo
    }

    @Test
    fun `multiple jobs are found when the short name matches`() {
        // Given that a couple of jobs exist with matching short names
        val jobOne = job(name = "build", fullName = "android/build")
        val jobTwo = job(name = "build", fullName = "ios/build")
        jenkins.jobs = listOf(jobOne, jobTwo)

        // When we search for a job by short name
        val result = jobFinder.findJob("build")

        // Then we get multiple matches
        result.isLeft() shouldBe true
        val error = result.left
        error shouldBeInstanceOf MultipleMatchingJobs::class.java
        (error as MultipleMatchingJobs).jobs shouldEqual listOf(jobOne, jobTwo)
    }

    @Test
    fun `a job cannot be found if the user does not have read permission`() {
        // Given that jobs exist, but cannot be seen
        jenkins.jobs = listOf(
            job(name = "build", fullName = "android/build", hasReadPermission = false),
            job(name = "build", fullName = "ios/build", hasReadPermission = false)
        )

        // When we search for a job with that name
        val result = jobFinder.findJob("build")

        // Then we don't get any matching jobs
        result.isLeft() shouldBe true
        result.left shouldBe NoMatchingJobs
    }

    @Test
    fun `an error is returned if the user does not have build permission`() {
        // Given that a job exists, and can be seen, but cannot be built
        val job = job(name = "one", hasBuildPermission = false)
        jenkins.jobs = listOf(job)

        // When we search for a job with that name
        val result = jobFinder.findJob("one")

        // Then we get an error
        result.isLeft() shouldBe true
        val error = result.left
        error shouldBeInstanceOf JobBuildNotPermissible::class.java
        (error as JobBuildNotPermissible).job shouldEqual job
    }


    @Test
    fun `an error is returned if the build is disabled`() {
        // Given that a job exists, and can be seen, but cannot be built
        val job = job(name = "one", isBuildable = false)
        jenkins.jobs = listOf(job)

        // When we search for a job with that name
        val result = jobFinder.findJob("one")

        // Then we get an error
        result.isLeft() shouldBe true
        val error = result.left
        error shouldBeInstanceOf JobNotBuildable::class.java
        (error as JobNotBuildable).job shouldEqual job
    }

    private val Either<*, *>.left
        get() = (this as Either.Left).a

    private val Either<*, *>.right
        get() = (this as Either.Right).b

    private fun job(
        name: String,
        fullName: String = name,
        isBuildable: Boolean = true,
        hasReadPermission: Boolean = true,
        hasBuildPermission: Boolean = true
    ): Job<*, *> {
        val job: Job<*, *> = mock(Job::class)
        whenever(job.getName()).thenReturn(name)
        whenever(job.getFullName()).thenReturn(fullName)
        whenever(job.isBuildable()).thenReturn(isBuildable)
        whenever(job.hasPermission(Item.READ)).thenReturn(hasReadPermission)
        whenever(job.hasPermission(Item.BUILD)).thenReturn(hasBuildPermission)
        return job
    }

}
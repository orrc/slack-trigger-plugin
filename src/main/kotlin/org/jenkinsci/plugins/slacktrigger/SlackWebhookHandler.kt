package org.jenkinsci.plugins.slacktrigger

import hudson.model.*
import hudson.security.ACL
import hudson.security.Permission
import jenkins.model.Jenkins
import jenkins.model.ParameterizedJobMixIn
import net.sf.json.JSONObject
import org.acegisecurity.Authentication
import org.jenkinsci.plugins.slacktrigger.SlackWebhookHandler.ResponseType
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import java.util.logging.Logger

class SlackWebhookHandler(
    private val token: String?,
    private val sslCheck: String?,
    private val command: String?,
    private val teamDomain: String?,
    private val channelName: String?,
    private val userId: String?,
    private val userName: String?,
    private val text: String?,
    private val responseUrl: String?
) {

    private val logger = Logger.getLogger(SlackWebhookHandler::class.java.name)

    fun execute(): HttpResponse {
        println("Start of HTTP command with context: ${User.current()}")
        val response = executeInternal()
        println("End of HTTP command with context: ${User.current()}")
        return createHttpResponse(response)
    }

    private fun executeInternal(): Response {
        // TODO: Verify token against config
        if (token == "TODO-read-from-config") {
            logger.fine("Ignoring Slack webhook as it has an unexpected verification token: '$token'")
            val configUrl = Jenkins.getInstance().rootUrl + "configure"
            return ChannelResponse(":fire: Jenkins does not have the correct Slack verification token configured: " +
                    configUrl)
        }

        // Respond to pings from Slack checking whether we're properly configured
        if (sslCheck == "1") {
            logger.fine("Responding to ssl_check probe from Slack")
            return PlainResponse(":+1: Hello, ssl_check!")
        }

        // Check that the command name looks sane
        if (command == null || !command.startsWith("/")) {
            logger.fine("Ignoring Slack webhook with unexpected command name, or missing slash prefix: '$command'")
            return ChannelResponse(":fire: Unknown command: $command")
        }

        // Do some sanity checks, so we know that we have valid data
        if (teamDomain == null || channelName == null
                || userId == null || userName == null || text == null || responseUrl == null) {
            logger.warning("""
                Not enough info in Slack webhook; one of these expected properties was null:
                - command:      $command
                - team_domain:  $teamDomain
                - channel_name: $channelName
                - user_id:      $userId
                - user_name:    $userName
                - text:         $text
                - response_url: $responseUrl
                """.trimIndent()
            )
            return UserResponse(":fire: Slack did not send the expected data… cannot start a build!")
        }

        // Check for certain command types
        when (text) {
            "connect" -> return connectSlackUser(userId, userName)
            "help" -> return createHelpResponse(command)
        }

        // Otherwise, we're ready to attempt to build something…
        return findJobAndExecuteBuild(command, teamDomain, channelName, userId, userName, text, responseUrl)
    }

    private fun connectSlackUser(userId: String, userName: String): Response {
        // TODO: Check whether Jenkins has the Slack OAuth properties configured

        // Check whether we're already connected
        SlackToJenkinsUserResolver.resolve(userId, userName)?.let {
            // TODO: Allow reconnect button anyway
            return UserResponse("""You're already connected as "${it.id}" on Jenkins. :ok_hand:""")
        }

        // Prompt user to link their account
        val url = Jenkins.getInstance().rootUrl + URL_NAMESPACE + "/connect"
        return UserResponse("""
            By connecting Jenkins with your Slack account, you'll be able to trigger builds of jobs that you have
            permission to access. Click here to connect:
            $url
            """.trimIndent())
    }

    private fun findJobAndExecuteBuild(
            command: String,
            teamDomain: String,
            channelName: String,
            userId: String,
            userName: String,
            text: String,
            responseUrl: String
    ): Response {
        // Attempt to find a job name in the Slack message
        val jobSearchText = extractJobSearchText(text)
        if (jobSearchText == null) {
            return UserResponse("""
                :confused: Couldn't find a job name in what you wrote; try something like this:
                > $command some job
                """.trimIndent()
            )
        }

        // Attempt to resolve the Slack user to a Jenkins user;
        // this lets us find jobs and execute builds on their behalf
        val user = SlackToJenkinsUserResolver.resolve(userId, userName)
        println("Resolved Slack user: $user")

        // Impersonate the user, falling back to anonymous
        return user.runAs {
            findJobAndExecuteBuild2(command, teamDomain, channelName, userId, userName, jobSearchText, responseUrl)
        }
    }

    private fun findJobAndExecuteBuild2(
            command: String,
            teamDomain: String,
            channelName: String,
            userId: String,
            userName: String,
            jobSearchText: String,
            responseUrl: String
    ): Response {
        // Find jobs which match the given text
        val jobs = findMatchingJobs(jobSearchText)
        if (jobs.isEmpty()) {
            return UserResponse("""
                :warning: Could not find a job called "$jobSearchText" — do you have permission to view it?
            """.trimIndent())
        }

        // Ask the user to disambiguate, if multiple jobs with the same short name were found
        if (jobs.size > 1) {
            val msg = jobs.fold(":thinking_face: There are multiple jobs with that name; " +
                    "please specify the full name:") { msg, job ->
                "$msg\n:small_blue_diamond: `$command ${job.getFullName()}`" // TODO: Parameters
            }
            return UserResponse(msg)
        }

        // Check whether the job can be built
        val job = jobs.first()
        if (!job.isBuildable()) {
            return UserResponse(""":no_entry_sign: The job "${job.getFullName()}" is currently disabled""")
        }

        if (!job.hasPermission(Job.BUILD)) {
            return UserResponse(""":no_entry: You don't have permission to build "${job.getFullName()}"""")
        }

        // TODO: Create action, stashing the responseUrl for later use…

        // Start build, attributing it to the user
        // TODO: With parameters
        val cause = createBuildTriggerCause(teamDomain, channelName, userId, userName)
        job.scheduleBuild(cause = cause)

        return ChannelResponse(""":bulb: Successfully triggered a build of "$jobSearchText"""")
    }

    private fun createHttpResponse(response: Response) = createHttpResponse(response.type, response.message)

    private fun createHttpResponse(responseType: ResponseType, message: String): HttpResponse {
        // Just send plain text, if we're not responding to a Slack channel
        if (responseType == ResponseType.NONE) {
            return HttpResponses.plainText(message)
        }

        val payload = JSONObject().apply {
            put("response_type", responseType.toSlackValue())
            put("text", message)
        }
        return HttpResponse { _, rsp, _ ->
            val bytes = payload.toString().toByteArray()
            rsp.contentType = "application/json; charset=UTF-8"
            rsp.setContentLength(bytes.size)
            rsp.outputStream.write(bytes)
        }
    }

    private fun createHelpResponse(command: String) =
            UserResponse("""
                :small_blue_diamond: Start a build of "my job":
                > `$command my job`
                :small_blue_diamond: Start a build of the "deploy" job in the "Web Site" folder:
                > `$command Web Site/deploy`
                :small_blue_diamond: Start a build that takes parameters:
                > `$command my job BRANCH=release/4.2 DEPLOY=true`
               """.trimIndent()
            )

    // TODO: Parameters
    private fun Job<*, *>.scheduleBuild(quietPeriod: Int = 0, cause: Cause, actions: List<Action> = emptyList()) {
        val allActions = actions.toMutableList().apply { add(CauseAction(cause)) }
        ParameterizedJobMixIn.scheduleBuild2(this, quietPeriod, *allActions.toTypedArray())
    }

    // TODO: Should be built in to Kotlin
    private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
        return try {
            block(this)
        } finally {
            this?.close()
        }
    }

    private inline fun <R> User?.runAs(block: () -> R): R =
            this?.let { impersonate().runAs { block() } } ?: block()

    private inline fun <R> Authentication.runAs(block: () -> R): R =
            ACL.`as`(this).use { block() }

    private fun extractJobSearchText(message: String): String? {
        // TODO: For now, we don't support parameters, so the whole message is the job name
        return message
    }

    private fun findMatchingJobs(jobName: String): List<Job<*, *>> {
        return Jenkins.getInstance().getAllItems(Job::class.java)
                .asSequence()
                .filter { it.hasPermission(Item.READ) }
                .filter { jobName == it.getName() || jobName == it.getFullName() }
                .toList()
    }

    private fun createBuildTriggerCause(
            teamDomain: String, channelName: String, userId: String, userName: String
    ): SlackTriggerCause {
        // If there's a channel called "#directmessage", then tough luck; that's what you get for crazy naming.
        // We could look at the channel ID, but I don't see a guarantee that channel_id for a DM starts with 'D'
        val channel = if (channelName == "directmessage") null else channelName
        return SlackTriggerCause(teamDomain, channel, userId, userName)
    }

    internal enum class ResponseType(private val slackValue: String?) {
        USER("ephemeral"),
        CHANNEL("in_channel"),
        NONE(null);

        fun toSlackValue() = slackValue
    }
}

private sealed class Response(val type: ResponseType, open val message: String)
private class ChannelResponse(override val message: String) : Response(ResponseType.CHANNEL, message)
private class UserResponse(override val message: String) : Response(ResponseType.USER, message)
private class PlainResponse(override val message: String) : Response(ResponseType.NONE, message)

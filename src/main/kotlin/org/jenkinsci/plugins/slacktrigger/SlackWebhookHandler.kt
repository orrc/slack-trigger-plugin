package org.jenkinsci.plugins.slacktrigger

import hudson.model.*
import hudson.security.ACL
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
        val response = executeInternal()
        return createHttpResponse(response)
    }

    private fun executeInternal(): Response {
        // Check that the verification token has been configured
        val expectedToken = SlackGlobalConfiguration.get().verificationToken
        if (expectedToken == null) {
            logger.warning("Ignoring Slack webhook as verification token is configured")
            return ChannelResponse(":fire: Jenkins does not have the Slack verification token configured: " +
                    Jenkins.getInstance().configUrl())
        }

        // Verify token against configured value
        if (token != expectedToken) {
            logger.info("Ignoring Slack webhook as it has an unexpected verification token: '$token'")
            return ChannelResponse(":fire: Jenkins does not have the correct Slack verification token configured: " +
                    Jenkins.getInstance().configUrl())
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
        when (text.trim()) {
            "connect" -> return connectSlackUser(userId, userName)
            "", "help" -> return createHelpResponse(command)
        }

        // Otherwise, we're ready to attempt to build something…
        return findJobAndExecuteBuild(command, teamDomain, channelName, userId, userName, text, responseUrl)
    }

    private fun connectSlackUser(userId: String, userName: String): Response {
        // TODO: Check whether Jenkins has the Slack OAuth properties configured

        // Check whether we're already connected
        SlackToJenkinsUserResolver.resolve(userId, userName)?.let {
            // TODO: Allow reconnect button anyway
            // TODO: Or validate that we actually are still connected via the Slack API
            return UserResponse("""You're already connected as "${it.id}" on Jenkins. :ok_hand:""")
        }

        // Prompt user to link their account
        val url = SlackConnectEndpoint.connectUrl
        return UserResponse("""
            :point_up: By connecting Jenkins with your Slack account, you'll be able to trigger builds of jobs for
            which you have permission. Click here to connect: $url
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
            val connectMsg = when (SlackToJenkinsUserResolver.resolve(userId, userName)) {
                null -> ":lock: Use `$command connect` to connect Jenkins with your Slack account"
                else -> ""
            }
            return UserResponse("""
                :warning: Could not find a job called "$jobSearchText" — do you have permission to view it?
                $connectMsg
                """.trimIndent())
        }

        // Ask the user to disambiguate, if multiple jobs with the same short name were found
        if (jobs.size > 1) {
            val msg = jobs.fold(":thinking_face: There are multiple jobs with that name; " +
                    "please specify the full name:") { msg, job ->
                "$msg\n:small_blue_diamond: ${job.slackLink()}" // TODO: Include configured parameter names?
            }
            return UserResponse(msg)
        }

        // Check whether the job can be built
        val job = jobs.first()
        if (!job.isBuildable()) {
            return ChannelResponse(""":no_entry_sign: The job "${job.slackLink()}" is currently disabled""")
        }

        // Check whether the user is allowed to build this job
        if (!job.hasPermission(Job.BUILD)) {
            val connectMsg = when (SlackToJenkinsUserResolver.resolve(userId, userName)) {
                null -> ":lock: Use `$command connect` to connect Jenkins with your Slack account"
                else -> ""
            }
            return UserResponse("""
                :no_entry: You don't have permission to build "${job.slackLink()}"
                $connectMsg
                """.trimIndent())
        }

        // TODO: Create action, stashing the responseUrl for later use…

        // Start build, attributing it to the user
        // TODO: With parameters
        val cause = createBuildTriggerCause(teamDomain, channelName, userId, userName)
        job.scheduleBuild(cause = cause)

        return ChannelResponse(""":bulb: Successfully triggered a build of "${job.slackLink()}"""")
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
                :wave: You can use this command to trigger builds on <${Jenkins.getInstance().rootUrl}|Jenkins>:
                :small_blue_diamond: Start a build of "my job":
                > `$command my job`
                :small_blue_diamond: Start a build of the "deploy" job in the "Web Site" folder:
                > `$command Web Site/deploy`
                :small_orange_diamond: Starting a build of a parameterised job will use the configured default values;
                this command does not _yet_ support specifying parameter values when triggering a build.
                :lock: Connect Jenkins with your Slack account, allowing you to build jobs you have access to:
                > `$command connect`
               """.trimIndent()
            )

    // TODO: Parameters
    private fun Job<*, *>.scheduleBuild(quietPeriod: Int = 0, cause: Cause, actions: List<Action> = emptyList()) {
        val allActions = actions.toMutableList().apply { add(CauseAction(cause)) }
        ParameterizedJobMixIn.scheduleBuild2(this, quietPeriod, *allActions.toTypedArray())
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
        // We could look at the channel ID, but I don't see a guarantee that channel_id for a DM starts with 'D'.
        // The same for private channels, which appear as "privategroup" (whose channel_id tends to start with 'G')
        val channel = when (channelName) {
            "directmessage", "privategroup" -> null
            else -> channelName
        }
        return SlackTriggerCause(teamDomain, channel, userId, userName)
    }

    /** @return A link to the job, showing its name, using the Slack message syntax. */
    private fun Job<*, *>.slackLink(): String {
        val url = Jenkins.getInstance().rootUrl + this.getUrl()
        return "<$url|${this.getFullName()}>"
    }

    private fun Jenkins.configUrl() = this.rootUrl + "configure"

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

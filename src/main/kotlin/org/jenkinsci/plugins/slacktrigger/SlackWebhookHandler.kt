package org.jenkinsci.plugins.slacktrigger

import arrow.core.getOrHandle
import hudson.model.Action
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.Job
import hudson.model.User
import jenkins.model.Jenkins
import jenkins.model.ParameterizedJobMixIn
import java.util.logging.Logger

internal class SlackWebhookHandler(
    private val request: SlackWebhookRequest,
    private val signingSecret: String,
    private val jenkins: Jenkins,
    private val jobFinder: JobFinder = JobFinder(jenkins)
) {

    private val logger = Logger.getLogger(SlackWebhookHandler::class.java.name)

    fun execute(): Response {
        // Check that the incoming webhook request was signed by Slack
        request.validateSignature(signingSecret).mapLeft {
            return when (it) {
                is MissingHeaderError -> {
                    logger.warning("Ignoring Slack webhook due to missing X-Slack-{Request-Timestamp,Signature} " +
                            "headers")
                    UserResponse(":face_with_raised_eyebrow: Jenkins received an invalid message from Slack. " +
                            "Try again.")
                }
                is InvalidSignatureError -> {
                    logger.info("Ignoring Slack webhook as it was not signed with the expected secret: " +
                            "'$signingSecret'")
                    ChannelResponse(":fire: Jenkins could not verify the message from Slack. Is the correct signing " +
                            "secret configured? " + jenkins.configUrl())
                }
            }
        }

        // Respond to pings from Slack checking whether we're properly configured
        if (request.sslCheck == "1") {
            logger.fine("Responding to ssl_check probe from Slack")
            return PlainResponse(":+1: Hello, ssl_check!")
        }

        // Check that the command name looks sane
        val command = request.command
        if (command == null || !command.startsWith("/")) {
            logger.info("Ignoring Slack webhook with unexpected command name, or missing slash prefix: '$command'")
            return ChannelResponse(":fire: Unknown command: $command")
        }

        // Do some sanity checks, so we know that we have valid data
        val teamDomain = request.teamDomain
        val channelName = request.channelName
        val userId = request.userId
        val userName = request.userName
        val messageText = request.messageText
        val responseUrl = request.responseUrl
        if (teamDomain == null || channelName == null
                || userId == null || userName == null || messageText == null || responseUrl == null) {
            logger.warning("""
                Not enough info in Slack webhook; one of these expected properties was null:
                - command:      $command
                - team_domain:  $teamDomain
                - channel_name: $channelName
                - user_id:      $userId
                - user_name:    $userName
                - text:         $messageText
                - response_url: $responseUrl
                """.trimIndent()
            )
            return UserResponse(":fire: Slack did not send the expected data… cannot start a build! Try again? :shrug:")
        }

        // Check for certain command types
        when (messageText.trim()) {
            "connect" -> return connectSlackUser(userId, userName)
            "disconnect" -> return disconnectSlackUser(userId, userName)
            "", "help" -> return createHelpResponse(command)
        }

        // Otherwise, we're ready to attempt to build something…
        return findJobAndExecuteBuild(command, teamDomain, channelName, userId, userName, messageText, responseUrl)
    }

    private fun findJobAndExecuteBuild(
        command: String,
        teamDomain: String,
        channelName: String,
        userId: String,
        userName: String,
        messageText: String,
        responseUrl: String
    ): Response {
        // Attempt to resolve the Slack user to a Jenkins user;
        // this lets us find jobs and execute builds on their behalf
        val jenkinsUser = SlackToJenkinsUserResolver.resolve(userId, userName)

        // Attempt to find a matching job, as the user
        val jobResult = jenkinsUser.runAs {
            jobFinder.findJob(messageText)
        }

        // If we couldn't find a job to be built, return an error
        val job = jobResult.getOrHandle {
            return getJobSearchErrorResponse(it, jenkinsUser, command, messageText)
        }
        return jenkinsUser.runAs {
            executeBuildAsUser(job, teamDomain, channelName, userId, userName, responseUrl)
        }
    }

    private fun getJobSearchErrorResponse(
        error: JobSearchError,
        jenkinsUser: User?,
        command: String,
        jobSearchText: String
    ): Response {
        return when (error) {
            NoMatchingJobs -> {
                val connectMsg = when (jenkinsUser) {
                    null -> ":lock: Use `$command connect` to connect Jenkins with your Slack account"
                    else -> ""
                }
                UserResponse("""
                        :warning: Could not find a job called "$jobSearchText" — do you have permission to view it?
                        $connectMsg
                    """.trimIndent())
            }
            is MultipleMatchingJobs -> {
                val msg = error.jobs.fold(""":thinking_face: There are multiple jobs called "$jobSearchText"; """ +
                        "try again with the full name:") { msg, job ->
                    "$msg\n:small_blue_diamond: ${job.slackLink()}" // TODO: Include configured parameter names?
                }
                UserResponse(msg)
            }
            is JobNotBuildable -> {
                ChannelResponse(""":no_entry_sign: The job "${error.job.slackLink()}" is currently disabled""")
            }
            is JobBuildNotPermissible -> {
                val connectMsg = when (jenkinsUser) {
                    null -> ":lock: Use `$command connect` to connect Jenkins with your Slack account"
                    else -> ""
                }
                UserResponse("""
                        :no_entry: You don't have permission to build "${error.job.slackLink()}"
                        $connectMsg
                    """.trimIndent())
            }
        }
    }

    private fun executeBuildAsUser(
            job: Job<*, *>,
            teamDomain: String,
            channelName: String,
            userId: String,
            userName: String,
            responseUrl: String
    ): Response {
        // TODO: Create action, stashing the responseUrl for later use…

        // Start build, attributing it to the user
        // TODO: With parameters
        val cause = createBuildTriggerCause(teamDomain, channelName, userId, userName)
        job.scheduleBuild(cause = cause)

        return ChannelResponse(""":bulb: Successfully triggered a build of "${job.slackLink()}"""")
    }

    private fun connectSlackUser(userId: String, userName: String): Response {
        // Check whether Jenkins has the Slack OAuth properties configured
        if (!SlackGlobalConfiguration.hasOauthConfigured()) {
            return ChannelResponse(":fire: Jenkins does not have the Slack OAuth client ID and secret configured: " +
                    jenkins.configUrl())
        }

        // Check whether we're already connected
        SlackToJenkinsUserResolver.resolve(userId, userName)?.let {
            return UserResponse("""You're already connected as "${it.id}" on Jenkins. :ok_hand:""")
        }

        // Prompt user to link their account
        val url = SlackConnectEndpoint.connectUrl
        return UserResponse("""
            :point_up: By connecting Jenkins with your Slack account, you'll be able to trigger builds of jobs for
            which you have permission. Click here to connect: $url
            """.trimIndent())
    }

    private fun disconnectSlackUser(userId: String, userName: String): Response {
        SlackToJenkinsUserResolver.resolve(userId, userName)?.disableSlackAccountUserProperty()
        return UserResponse(":raised_hands: Your Slack account has been disconnected from Jenkins.")
    }

    private fun createHelpResponse(command: String) =
            UserResponse("""
                :wave: You can use this command to trigger builds on <${jenkins.rootUrl}|Jenkins>:
                :small_blue_diamond: Start a build of "my job":
                > `$command my job`
                :small_blue_diamond: Start a build of the "deploy" job in the "Web Site" folder:
                > `$command Web Site/deploy`
                :small_orange_diamond: Starting a build of a parameterised job will use the configured default values;
                this command does not _yet_ support specifying parameter values when triggering a build.
                :lock: Connect Jenkins with your Slack account, allowing you to build jobs you have access to:
                > `$command connect`
                :unlock: Disconnect your Slack account from your Jenkins account:
                > `$command disconnect`
               """.trimIndent()
            )

    // TODO: Parameters
    private fun Job<*, *>.scheduleBuild(quietPeriod: Int = 0, cause: Cause, actions: List<Action> = emptyList()) {
        val allActions = actions.toMutableList().apply { add(CauseAction(cause)) }
        ParameterizedJobMixIn.scheduleBuild2(this, quietPeriod, *allActions.toTypedArray())
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
        val url = jenkins.rootUrl + this.getUrl()
        return "<$url|${this.getFullName()}>"
    }

}

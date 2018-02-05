package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.model.User
import hudson.security.csrf.CrumbExclusion
import hudson.util.HttpResponses
import net.sf.json.JSONException
import net.sf.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.WebMethod
import java.io.IOException
import java.util.logging.Logger
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** URL namespace for all endpoints in this plugin. */
internal const val URL_NAMESPACE = "slack-trigger"

@Extension
class SlackWebhookEndpoint : UnprotectedRootAction {

    private val logger = Logger.getLogger(SlackWebhookEndpoint::class.java.name)

    override fun getUrlName() = URL_NAMESPACE

    @Suppress("unused") // Called by Stapler
    fun doBuild(
            @QueryParameter("token") token: String?,
            @QueryParameter("ssl_check") sslCheck: String?,
            @QueryParameter("command") command: String?,
            @QueryParameter("team_domain") teamDomain: String?,
            @QueryParameter("channel_name") channelName: String?,
            @QueryParameter("user_id") userId: String?,
            @QueryParameter("user_name") userName: String?,
            @QueryParameter("text") text: String?,
            @QueryParameter("response_url") responseUrl: String?
    ): HttpResponse {
        return SlackWebhookHandler(token, sslCheck, command, teamDomain, channelName, userId, userName, text,
                responseUrl).execute()
    }

    @Suppress("unused") // Called by Stapler
    fun doConnect(): HttpResponse {
        // Check whether the user is logged in
        if (User.current() == null) {
            return HttpResponses.plainText("You must log in to Jenkins first.")
        }

        // Redirect to Slack OAuth2 page
        return HttpResponses.redirectTo(getOAuth2Url())
    }

    @WebMethod(name = ["oauth2_response"])
    @Suppress("unused") // Called by Stapler
    fun handleOAuth2Response(
            @QueryParameter("code") code: String?,
            @QueryParameter("error") error: String?
    ): HttpResponse {
        // Check for user logged in
        val currentUser = User.current() ?: return HttpResponses.plainText("You must log in to Jenkins first.")

        // Check for user cancellation
        if (error != null) {
            logger.info("""Failed to connect "${currentUser.id}" Slack via OAuth 2.0: $error""")
            return HttpResponses.plainText("You cancelled the request to connect, or something went wrong.")
        }

        // Check for valid code in response
        if (code == null) {
            logger.fine("Unexpected Slack OAuth 2.0 response: code was missing.")
        }

        // Build Slack API request for user data
        val clientId = "1234556789.54321" // TODO
        val clientSecret = "loremipsumfoobarbaz" // TODO
        val url = HttpUrl.parse("https://slack.com/api/oauth.access")!!
                .newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("client_secret", clientSecret)
                .addQueryParameter("code", code)
                .build()
        val request = Request.Builder().url(url).build()

        // Attempt to call the Slack API to fetch the user's account info
        val response: Response
        try {
            response = OkHttpClient().newCall(request).execute()
        } catch (ex: IOException) {
            logger.info("""Failed to get user info for "${currentUser.id}" from Slack API: $ex""")
            val message = ex.message.redact(clientSecret)
            return HttpResponses.plainText("Try again. An error occurred while attempting to reach Slack: $message")
        }

        // We expect a nice response from Slack
        val body = response.body()?.string()
        if (!response.isSuccessful) {
            logger.info("""Fetching user info for "${currentUser.id}" from Slack returned: ${response.code()}""")
            return HttpResponses.plainText("""
                Try again. Slack returned an unexpected response:
                - HTTP code: ${response.code()}
                - Response:
                ${body?.substring(0..10000).redact(clientSecret)}
                """.trimIndent())
        }

        // Parse the response as JSON
        val apiResponse: JSONObject
        try {
            apiResponse = JSONObject.fromObject(body)
            if (!apiResponse.optBoolean("ok")) {
                logger.info("""
                    Fetching user info for "${currentUser.id}" from Slack gave non-ok response:
                    ${body?.substring(0..2000).redact(clientSecret)}
                    """.trimIndent())
                return HttpResponses.plainText("Try again. Something went wrong while connecting to Slack.")
            }
        } catch (ex: JSONException) {
            val shortBody = body?.substring(0..2000)
            logger.info("""Fetching user info for "${currentUser.id}" from Slack returned non-JSON: $shortBody""")
            return HttpResponses.plainText("""
                    Try again. Slack returned an unexpected response:
                    ${body?.substring(0..10000).redact(clientSecret)}
                    """.trimIndent())
        }

        // Grab the stuff we want from the JSON response
        val teamId = apiResponse.optJSONObject("team")?.optString("id")
        val userId = apiResponse.optJSONObject("user")?.optString("id")
        if (teamId == null || userId == null) {
            val shortBody = body?.substring(0..2000)
            logger.info("""Fetching user info for "${currentUser.id}" from Slack returned unexpected JSON:
                $shortBody""".trimIndent())
            return HttpResponses.plainText("Try again. Something went wrong while connecting to Slack.")
        }

        // Store the user property
        val userProperty = SlackAccountUserProperty(teamId, userId, "TODO")
        currentUser.addProperty(userProperty)
        currentUser.save()

        // Done!
        logger.info("""Successfully linked "${currentUser.id}" with Slack user $teamId/$userId.""")
        return HttpResponses.plainText("Success! Your Slack account is now connected to Jenkins.")
    }

    private fun getOAuth2Url(): String {
        val clientId = "1234556789.54321" // TODO: Use OAuth2 settings
        return "https://slack.com/oauth/authorize?scope=identity.basic&client_id=$clientId" // TODO: Encode
    }

    private fun String?.redact(secret: String): String =
            when (this) {
                null -> "(unknown)"
                else -> this.replace(secret, "********")
            }

    // Unused overrides
    override fun getIconFileName() = null

    override fun getDisplayName() = "Slack Build TODO"

}

@Extension
class SlackCommandTriggerCrumbExclusion : CrumbExclusion() {

    private val exclusionPath = "/$URL_NAMESPACE/"

    override fun process(req: HttpServletRequest, resp: HttpServletResponse, chain: FilterChain): Boolean {
        if (req.pathInfo?.startsWith(exclusionPath) == true) {
            chain.doFilter(req, resp)
            return true
        }
        return false
    }

}

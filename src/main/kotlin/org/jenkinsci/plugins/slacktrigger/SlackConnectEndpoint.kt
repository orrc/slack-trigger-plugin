package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.RootAction
import hudson.model.User
import hudson.util.HttpResponses
import jenkins.model.Jenkins
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
import java.net.URLEncoder
import java.util.logging.Logger

/** URL namespace for all endpoints defined here. */
private const val URL_NAMESPACE = "slack-trigger-connect"

@Extension
class SlackConnectEndpoint : RootAction {

    private val logger = Logger.getLogger(SlackConnectEndpoint::class.java.name)

    companion object {
        val connectUrl = Jenkins.getInstance().rootUrl + URL_NAMESPACE + "/"
    }

    override fun getUrlName() = URL_NAMESPACE

    @Suppress("unused") // Called by Stapler
    fun doIndex(): HttpResponse {
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
        // TODO: Fancier pages for all responses, rather than just plain text, as these are all user-facing
        // Check whether OAuth2 values have been set
        if (!SlackGlobalConfiguration.hasOauthConfigured()) {
            return HttpResponses.plainText("Jenkins does not have the Slack OAuth client ID and secret configured.")
        }

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
        val clientId = SlackGlobalConfiguration.get().clientId
        val clientSecret = SlackGlobalConfiguration.get().clientSecret!!.plainText
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
                ${body.redact(clientSecret).truncate(10000)}
                """.trimIndent())
        }

        // Parse the response as JSON
        val apiResponse: JSONObject
        try {
            apiResponse = JSONObject.fromObject(body)
            if (!apiResponse.optBoolean("ok")) {
                logger.info("""
                    Fetching user info for "${currentUser.id}" from Slack gave non-ok response:
                    ${body.redact(clientSecret).truncate(2000)}
                    """.trimIndent())
                return HttpResponses.plainText("Try again. Something went wrong while connecting to Slack.")
            }
        } catch (ex: JSONException) {
            val shortBody = body.truncate(2000)
            logger.info("""Fetching user info for "${currentUser.id}" from Slack returned non-JSON: $shortBody""")
            return HttpResponses.plainText("""
                    Try again. Slack returned an unexpected response:
                    ${body.redact(clientSecret).truncate(10000)}
                    """.trimIndent())
        }

        // Grab the stuff we want from the JSON response
        val teamId = apiResponse.optJSONObject("team")?.optString("id")
        val userId = apiResponse.optJSONObject("user")?.optString("id")
        if (teamId == null || userId == null) {
            val shortBody = body.truncate(2000)
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

    private fun String?.redact(secret: String): String =
            when (this) {
                null -> "(unknown)"
                else -> this.replace(secret, "********")
            }

    private fun String?.truncate(maxLength: Int): String =
            when (this) {
                null -> ""
                else -> this.substring(0 until Math.min(this.length, maxLength))
            }


    private fun getOAuth2Url(): String {
        val clientId = SlackGlobalConfiguration.get().clientId.let {
            URLEncoder.encode(it, Charsets.UTF_8.name())
        }
        return "https://slack.com/oauth/authorize?scope=identity.basic&client_id=$clientId"
    }

    // Unused overrides
    override fun getIconFileName() = null

    override fun getDisplayName() = null

}

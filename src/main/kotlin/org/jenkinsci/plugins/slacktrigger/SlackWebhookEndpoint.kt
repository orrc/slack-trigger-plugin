package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.security.csrf.CrumbExclusion
import jenkins.model.Jenkins
import net.sf.json.JSONObject
import org.kohsuke.stapler.Header
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.HttpResponses
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.interceptor.RequirePOST
import java.util.logging.Logger
import javax.inject.Inject
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** URL namespace for all endpoints defined here. */
private const val URL_NAMESPACE = "slack-trigger"

@Extension
class SlackWebhookEndpoint : UnprotectedRootAction {

    @Inject
    private lateinit var jenkins: Jenkins

    private val logger = Logger.getLogger(SlackWebhookEndpoint::class.java.name)

    override fun getUrlName() = URL_NAMESPACE

    @RequirePOST
    @Suppress("unused") // Called by Stapler
    fun doBuild(
        @Header("X-Slack-Request-Timestamp") requestTimestamp: String?,
        @Header("X-Slack-Signature") requestSignature: String?,
        request: StaplerRequest
    ): HttpResponse {
        // Check that the signing secret has been configured
        val signingSecret = SlackGlobalConfiguration.get().requestSigningSecret?.plainText
        if (signingSecret == null) {
            logger.warning("Ignoring incoming Slack webhook as signing secret has not been configured")
            val response = ChannelResponse(":fire: Jenkins does not have the Slack signing secret configured: " +
                    jenkins.configUrl())
            return createHttpResponse(response)
        }

        // Slack request body is UTF-8, yet they don't specify that in their Content-Type header…
        // (otherwise, we could just use `request.reader` and let it do any necessary translation)
        val requestBody = request.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val params = parseQueryString(requestBody)
        val requestWrapper = SlackWebhookRequest(
            requestBody, requestTimestamp, requestSignature,
            params["ssl_check"],
            params["command"],
            params["team_domain"],
            params["channel_name"],
            params["user_id"],
            params["user_name"],
            params["text"],
            params["response_url"]
        )

        // Handle the webhook and send a response to Slack
        val response = SlackWebhookHandler(requestWrapper, signingSecret, jenkins).execute()
        return createHttpResponse(response)
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

    // Unused overrides
    override fun getIconFileName() = null

    override fun getDisplayName() = null

}

internal sealed class Response(val type: ResponseType, open val message: String)
internal class ChannelResponse(override val message: String) : Response(ResponseType.CHANNEL, message)
internal class UserResponse(override val message: String) : Response(ResponseType.USER, message)
internal class PlainResponse(override val message: String) : Response(ResponseType.NONE, message)

internal enum class ResponseType(private val slackValue: String?) {
    USER("ephemeral"),
    CHANNEL("in_channel"),
    NONE(null);

    fun toSlackValue() = slackValue
}

@Extension
class SlackWebhookEndpointCrumbExclusion : CrumbExclusion() {

    private val exclusionPath = "/$URL_NAMESPACE/"

    override fun process(req: HttpServletRequest, resp: HttpServletResponse, chain: FilterChain): Boolean {
        if (req.pathInfo?.startsWith(exclusionPath) == true) {
            chain.doFilter(req, resp)
            return true
        }
        return false
    }

}

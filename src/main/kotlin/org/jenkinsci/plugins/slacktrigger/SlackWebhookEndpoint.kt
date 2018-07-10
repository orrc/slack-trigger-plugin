package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.security.csrf.CrumbExclusion
import org.kohsuke.stapler.Header
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.StaplerRequest
import org.kohsuke.stapler.interceptor.RequirePOST
import java.net.URLDecoder
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** URL namespace for all endpoints defined here. */
private const val URL_NAMESPACE = "slack-trigger"

@Extension
class SlackWebhookEndpoint : UnprotectedRootAction {

    override fun getUrlName() = URL_NAMESPACE

    @RequirePOST
    @Suppress("unused") // Called by Stapler
    fun doBuild(
            @Header("X-Slack-Request-Timestamp") requestTimestamp: String?,
            @Header("X-Slack-Signature") requestSignature: String?,
            request: StaplerRequest
    ): HttpResponse {
        // Slack request body is UTF-8, yet they don't specify that in their Content-Type header…
        // (otherwise, we could just use `request.reader` and let it do any necessary translation)
        val requestBody = request.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val params = parseQueryString(requestBody)
        return SlackWebhookHandler(
            requestBody, requestTimestamp, requestSignature,
            params["ssl_check"],
            params["command"],
            params["team_domain"],
            params["channel_name"],
            params["user_id"],
            params["user_name"],
            params["text"],
            params["response_url"]
        ).execute()
    }

    private fun parseQueryString(queryString: String): Map<String, String> =
        queryString
            .split('&')
            .map { param -> param.split('=') }
            .map { kv -> Pair(kv[0], URLDecoder.decode(kv[1], Charsets.UTF_8.name())) }
            .toMap()

    // Unused overrides
    override fun getIconFileName() = null

    override fun getDisplayName() = null

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

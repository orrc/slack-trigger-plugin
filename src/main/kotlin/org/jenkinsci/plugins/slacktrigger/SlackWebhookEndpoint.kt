package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.security.csrf.CrumbExclusion
import org.kohsuke.stapler.HttpResponse
import org.kohsuke.stapler.QueryParameter
import javax.servlet.FilterChain
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** URL namespace for all endpoints in this plugin. */
private const val URL_NAMESPACE = "slack-trigger"

@Extension
class SlackWebhookEndpoint : UnprotectedRootAction {

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

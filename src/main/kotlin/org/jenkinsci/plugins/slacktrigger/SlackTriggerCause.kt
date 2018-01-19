package org.jenkinsci.plugins.slacktrigger

import hudson.console.HyperlinkNote
import hudson.console.ModelHyperlinkNote
import hudson.model.Cause.UserIdCause
import hudson.model.TaskListener
import hudson.model.User

data class SlackTriggerCause(
        private val slackTeamDomain: String,
        private val slackChannelName: String?,
        private val slackUserId: String,
        private val slackUserName: String
) : UserIdCause() {

    override fun getShortDescription(): String =
            if (slackChannelName == null) {
                "Started by user $userName ($slackUserName) via Slack"
            } else {
                "Started by user $userName ($slackUserName) via #$slackChannelName on Slack"
            }

    override fun print(listener: TaskListener) {
        val userName =
                if (userId == null) {
                    "anonymous"
                } else {
                    val user = User.getById(userId, false)
                    if (user == null) {
                        userId
                    } else {
                        ModelHyperlinkNote.encodeTo(user)
                    }
                }
        val slackUserLink = getUserUrl(slackTeamDomain, slackUserId, slackUserName)
        val msg = if (slackChannelName == null) {
            "Started by user $userName ($slackUserLink) via Slack"
        } else {
            "Started by user $userName ($slackUserLink) via #$slackChannelName on Slack"
        }
        listener.logger.println(msg)
    }

    private fun getUserUrl(teamDomain: String, slackUserId: String, slackUserName: String): String {
        return HyperlinkNote.encodeTo("https://$teamDomain.slack.com/team/$slackUserId", slackUserName)
    }

}

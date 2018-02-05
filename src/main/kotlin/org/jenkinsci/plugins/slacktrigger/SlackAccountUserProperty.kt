package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.User
import hudson.model.UserProperty
import hudson.model.UserPropertyDescriptor
import org.kohsuke.stapler.DataBoundConstructor

data class SlackAccountUserProperty
@DataBoundConstructor constructor(
        val teamId: String,
        val slackUserId: String,
        val slackUserName: String
) : UserProperty() {

    fun getUser(): User = user

    @Extension
    class Descriptor : UserPropertyDescriptor() {
        override fun newInstance(user: User?): UserProperty? = null
        override fun getDisplayName() = "Slack"
    }

}
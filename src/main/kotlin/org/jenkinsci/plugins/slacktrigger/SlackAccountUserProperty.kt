package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.model.User
import hudson.model.UserProperty
import hudson.model.UserPropertyDescriptor
import hudson.util.FormValidation
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor

data class SlackAccountUserProperty
@DataBoundConstructor constructor(
        var teamId: String,
        var slackUserId: String,
        var slackUserName: String,
        var isActive: Boolean = true
) : UserProperty() {

    fun getUser(): User = user

    @Extension
    class Descriptor : UserPropertyDescriptor() {
        override fun newInstance(user: User?): UserProperty? = null
        override fun getDisplayName() = "Slack"

        @Suppress("unused") // Called by Stapler
        fun doDisconnect(@AncestorInPath user: User): FormValidation {
            // It doesn't seem that we can remove UserProperties? So mark it as inactive…
            user.getProperty(SlackAccountUserProperty::class.java)?.let {
                it.teamId = ""
                it.slackUserId = ""
                it.slackUserName = ""
                it.isActive = false
                user.save()
            }
            return FormValidation.ok("Disconnected!")
        }
    }

}
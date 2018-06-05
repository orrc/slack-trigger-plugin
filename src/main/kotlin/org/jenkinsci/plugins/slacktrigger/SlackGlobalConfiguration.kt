package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.util.Secret
import jenkins.model.GlobalConfiguration
import org.kohsuke.stapler.DataBoundSetter

@Extension
class SlackGlobalConfiguration : GlobalConfiguration() {

    var clientId: String? = null
        @DataBoundSetter set(value) {
            field = value
            save()
        }

    var clientSecret: Secret? = null
        @DataBoundSetter set(value) {
            field = value
            save()
        }

    var verificationToken: String? = null
        @DataBoundSetter set(value) {
            field = value
            save()
        }

    companion object {
        fun get() = GlobalConfiguration.all().get(SlackGlobalConfiguration::class.java)!!

        /** @return `true` if both the OAuth 2.0 client ID and secret have been configured. */
        fun hasOauthConfigured() = with (get()) {
                clientId != null && clientSecret != null
            }
    }

    init {
        // When Jenkins is restarted, load any saved configuration from disk
        load()
    }

}
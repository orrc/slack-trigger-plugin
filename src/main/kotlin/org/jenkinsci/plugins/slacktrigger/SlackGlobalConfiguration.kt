package org.jenkinsci.plugins.slacktrigger

import hudson.Extension
import hudson.util.Secret
import jenkins.model.GlobalConfiguration
import org.kohsuke.stapler.DataBoundSetter

@Extension
class SlackGlobalConfiguration : GlobalConfiguration() {

    var clientId: String? = null
        @DataBoundSetter set(value) {
            field = value?.trim()
            save()
        }

    var clientSecret: Secret? = null
        @DataBoundSetter set(value) {
            field = value?.trim()
            save()
        }

    var requestSigningSecret: Secret? = null
        @DataBoundSetter set(value) {
            field = value?.trim()
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

    /** Ensures that the text stored in the secret is trimmed. */
    private fun Secret?.trim(): Secret? = when (this) {
        null -> null
        else -> Secret.fromString(this.plainText.trim())
    }

}
package org.jenkinsci.plugins.slacktrigger

import com.google.common.collect.ImmutableList
import hudson.Extension
import hudson.ExtensionList
import hudson.ExtensionPoint
import hudson.model.AbstractDescribableImpl
import hudson.model.User
import hudson.model.UserProperty
import hudson.model.UserPropertyDescriptor
import org.kohsuke.stapler.DataBoundConstructor
import java.util.*

abstract class SlackToJenkinsUserResolver
    : ExtensionPoint, AbstractDescribableImpl<SlackToJenkinsUserResolver>() {

    /**
     * Resolves the given Slack user to a corresponding Jenkins [user][hudson.model.User], if possible.
     */
    abstract fun resolveUser(slackUserId: String, slackUsername: String): User?

    companion object {
        @JvmStatic
        fun all(): List<SlackToJenkinsUserResolver> {
            return ImmutableList.copyOf(ExtensionList.lookup(SlackToJenkinsUserResolver::class.java))
        }

        /**
         * Asks each known [SlackToJenkinsUserResolver] to resolve the given Slack user,
         * until either a Jenkins user is found, or none of the resolvers have an answer.
         */
        @JvmStatic
        fun resolve(slackUserId: String, slackUsername: String): User? {
            all().forEach {
                val user = it.resolveUser(slackUserId, slackUsername)
                if (user != null) {
                    return user
                }
            }
            return null
        }
    }
}


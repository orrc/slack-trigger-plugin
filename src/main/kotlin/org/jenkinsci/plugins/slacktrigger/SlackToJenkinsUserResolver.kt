package org.jenkinsci.plugins.slacktrigger

import com.google.common.collect.ImmutableList
import hudson.Extension
import hudson.ExtensionList
import hudson.ExtensionPoint
import hudson.model.AbstractDescribableImpl
import hudson.model.User

/** Resolves a Slack user to a corresponding Jenkins user, if possible. */
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
        fun resolve(slackUserId: String, slackUsername: String): User? =
            all()
                .asSequence()
                .map { it.resolveUser(slackUserId, slackUsername) }
                .firstOrNull()
    }
}

/** Resolver which returns Jenkins users who have successfully linked their Slack account via OAuth 2.0. */
@Extension
class AuthenticatedSlackUserResolver : SlackToJenkinsUserResolver() {
    override fun resolveUser(slackUserId: String, slackUsername: String): User? =
        User.getAll()
            .asSequence()
            .mapNotNull { it.getProperty(SlackAccountUserProperty::class.java) }
            .filter { it.slackUserId == slackUserId } // TODO: teamId
            .map { it.getUser() }
            .firstOrNull()
}

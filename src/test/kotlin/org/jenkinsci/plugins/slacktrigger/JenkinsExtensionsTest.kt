package org.jenkinsci.plugins.slacktrigger

import hudson.model.User
import org.amshove.kluent.shouldEqual
import org.junit.Rule
import org.junit.Test
import org.jvnet.hudson.test.JenkinsRule

class JenkinsExtensionsTest {

    @get:Rule
    val j = JenkinsRule()

    @Test
    fun `runAs without a valid user should execute as anonymous`() {
        (null as User?).runAs {
            User.current()?.id shouldEqual null
        }
    }

    @Test
    fun `runAs with a valid user should execute as that user`() {
        j.jenkins.securityRealm = j.createDummySecurityRealm()

        User.get("foo").runAs {
            User.current()?.id shouldEqual "foo"
        }
    }

}

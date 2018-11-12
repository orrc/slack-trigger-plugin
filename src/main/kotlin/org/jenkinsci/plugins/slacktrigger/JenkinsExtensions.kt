package org.jenkinsci.plugins.slacktrigger

import hudson.model.User
import hudson.security.ACL
import jenkins.model.Jenkins

fun Jenkins.configUrl() = this.rootUrl + "configure"

inline fun <R> User?.runAs(block: () -> R) =
        ACL.`as`(this).use { block() }

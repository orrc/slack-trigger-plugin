package org.jenkinsci.plugins.slacktrigger

import hudson.model.User
import hudson.security.ACL
import jenkins.model.Jenkins
import org.acegisecurity.Authentication

fun Jenkins.configUrl() = this.rootUrl + "configure"

inline fun <R> User?.runAs(block: () -> R): R =
        this?.let { impersonate().runAs { block() } } ?: block()

inline fun <R> Authentication.runAs(block: () -> R): R =
        ACL.`as`(this).use { block() }

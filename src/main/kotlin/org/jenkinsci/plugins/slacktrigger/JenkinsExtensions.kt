package org.jenkinsci.plugins.slacktrigger

import jenkins.model.Jenkins

fun Jenkins.configUrl() = this.rootUrl + "configure"

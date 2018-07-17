package org.jenkinsci.plugins.slacktrigger

import java.net.URLDecoder

// Not the smartest parser ever created, but handles the regular input we get from Slack
fun parseQueryString(queryString: String): Map<String, String> =
    queryString
        .split('&')
        .map { param -> param.split('=') }
        .filter { it.size == 2 }
        .map { kv -> Pair(kv[0], URLDecoder.decode(kv[1], Charsets.UTF_8.name())) }
        .toMap()

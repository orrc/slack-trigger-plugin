package org.jenkinsci.plugins.slacktrigger

internal data class SlackWebhookRequest(
    val requestBody: String,
    val timestampHeader: String?,
    val signatureHeader: String?,
    val sslCheck: String?,
    val command: String?,
    val teamDomain: String?,
    val channelName: String?,
    val userId: String?,
    val userName: String?,
    val text: String?,
    val responseUrl: String?
)
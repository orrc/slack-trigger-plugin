package org.jenkinsci.plugins.slacktrigger

import com.nhaarman.mockito_kotlin.whenever
import jenkins.model.Jenkins
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldContain
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class SlackWebhookHandlerTest {

    @Mock
    private lateinit var jenkins: Jenkins

    private val signingSecret: String = "secret!"
    private val jenkinsRootUrl = "https://ci.example.com/jenkins/"

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(jenkins.rootUrl).thenReturn(jenkinsRootUrl)
    }

    @Test
    fun `mismatched signatures are caught`() {
        // Given a webhook request signed with the usual secret
        val request = createRequest()

        // When we attempt to validate with a different secret
        val handler = SlackWebhookHandler(request, "different-secret", jenkins)
        val response = handler.execute()

        // Then the response should tell us about the signature mismatch
        response shouldBeInstanceOf ChannelResponse::class.java
        response.message shouldContain "could not verify"
    }

    @Test
    fun `ssl checks are done`() {
        // Given an SSL-checking webhook request
        val request = createRequest(sslCheck = "1")

        // When we attempt to handle it
        val response = request.execute()

        // Then the appropriate response should be returned to Slack
        response shouldBeInstanceOf PlainResponse::class.java
        response.message shouldContain "Hello, ssl_check!"
    }

    @Test
    fun `command is validated`() {
        // Given a webhook request with an invalid slash command
        val request = createRequest(command = "foo")

        // When we attempt to handle it
        val response = request.execute()

        // Then the response should show an error message to the channel
        response shouldBeInstanceOf ChannelResponse::class.java
        response.message shouldContain "Unknown command: foo"
    }

    @Test
    fun `providing no text shows help message`() {
        // Given an empty webhook request
        val request = createRequest()

        // When we attempt to handle it
        val response = request.execute()

        // Then the response should show the user some help text
        response shouldBeInstanceOf UserResponse::class.java
        response.message shouldContain "You can use this command to trigger builds"
        response.message shouldContain jenkinsRootUrl
    }

    private fun SlackWebhookRequest.execute() = SlackWebhookHandler(this, signingSecret, jenkins).execute()

    private fun createRequest(
        sslCheck: String? = null,
        command: String? = "/build",
        text: String? = "",
        teamDomain: String? = "slack.example.com",
        channelName: String? = "#dev",
        userId: String? = "U00000000",
        userName: String? = "Mr. Jenkins"
    ): SlackWebhookRequest {
        val requestBody = "{}"
        val timestampHeader = "123"
        val signatureHeader = SlackWebhookRequest.computeRequestSignature(requestBody, timestampHeader, signingSecret)
        return SlackWebhookRequest(
                requestBody, timestampHeader, signatureHeader,
                sslCheck, command, teamDomain, channelName, userId, userName, text,
                "https://slack.example.com/response"
        )
    }

}
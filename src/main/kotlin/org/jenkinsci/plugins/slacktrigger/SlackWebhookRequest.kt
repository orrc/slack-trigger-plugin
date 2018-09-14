package org.jenkinsci.plugins.slacktrigger

import arrow.core.Either
import org.apache.commons.codec.digest.HmacAlgorithms
import org.apache.commons.codec.digest.HmacUtils

internal data class SlackWebhookRequest(
    private val requestBody: String,
    private val timestampHeader: String?,
    private val signatureHeader: String?,
    val sslCheck: String?,
    val command: String?,
    val teamDomain: String?,
    val channelName: String?,
    val userId: String?,
    val userName: String?,
    val text: String?,
    val responseUrl: String?
) {

    fun validateSignature(signingSecret: String): Either<ValidationError, Boolean> {
        // Check whether the timestamp and signature are present
        if (timestampHeader == null || signatureHeader == null) {
            return Either.Left(MissingHeaderError)
        }

        // Determine the signature of the request body, and complain if it doesn't match
        val computedSignature = computeRequestSignature(requestBody, timestampHeader, signingSecret)
        if (computedSignature != signatureHeader) {
            return Either.left(InvalidSignatureError)
        }

        return Either.right(true)
    }

    companion object {
        /**
         * Computes the signature for the given request body and timestamp.
         *
         * See: https://api.slack.com/docs/verifying-requests-from-slack
         */
        fun computeRequestSignature(requestBody: String, requestTimestamp: String, signingSecret: String): String {
            val signaturePayload = "v0:$requestTimestamp:$requestBody"
            val hmac = HmacUtils(HmacAlgorithms.HMAC_SHA_256, signingSecret).hmacHex(signaturePayload)
            return "v0=$hmac"
        }
    }

}

internal sealed class ValidationError
internal object MissingHeaderError : ValidationError()
internal object InvalidSignatureError : ValidationError()

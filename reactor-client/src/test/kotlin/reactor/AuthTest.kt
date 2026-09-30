package reactor

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private fun client(server: MockWebServer, body: String): ReactorClient {
        server.enqueue(MockResponse().setBody(body).addHeader("content-type", "application/json"))
        return ReactorClient(server.url("/").toString().trimEnd('/'), "anon")
    }

    @Test
    fun verificationDoesNotStoreASession() = runBlocking {
        val server = MockWebServer()
        val client = client(server, """{"verification_required":true,"user":{"id":"u","email":"a@b.co"}}""")
        val outcome = client.auth.signUp("a@b.co", "password123") as AuthResult.VerificationRequired
        assertEquals("a@b.co", outcome.user.email)
        assertNull(client.auth.getSession())
        server.shutdown()
    }

    @Test
    fun mfaAndEnrollmentStayOutOfTheSession() = runBlocking {
        val server = MockWebServer()
        var client = client(server, """{"mfa_required":true,"mfa_token":"mfa","factors":["totp"]}""")
        val challenged = client.auth.signInWithPassword("a@b.co", "password123") as AuthResult.MfaRequired
        assertEquals("mfa", challenged.token)
        assertEquals(listOf("totp"), challenged.factors)
        assertNull(client.auth.getSession())

        client = client(server, """{"enrollment_required":true,"enroll_token":"enroll","factors":[]}""")
        val enroll = client.auth.signInWithPassword("a@b.co", "password123") as AuthResult.EnrollmentRequired
        assertEquals("enroll", enroll.token)
        assertNull(client.auth.getSession())
        server.shutdown()
    }

    @Test
    fun sessionAndOAuthCodeAreStored() = runBlocking {
        val server = MockWebServer()
        val session = """{"access_token":"access","refresh_token":"refresh","user":{"id":"u","email":"a@b.co"}}"""
        server.enqueue(MockResponse().setBody(session).addHeader("content-type", "application/json"))
        server.enqueue(MockResponse().setBody(session).addHeader("content-type", "application/json"))
        val client = ReactorClient(server.url("/").toString().trimEnd('/'), "anon")
        val verified = client.auth.verifyEmail(email = "a@b.co", code = "123456")
        assertEquals("access", verified.accessToken)
        assertEquals("access", client.auth.getSession()?.accessToken)
        val url = client.auth.signInWithOAuth("google", "https://app.example/cb")
        assertTrue(url.contains("/auth/v1/authorize"))
        assertTrue(url.contains("provider=google"))
        val exchanged = client.auth.exchangeCode("code")
        assertEquals("refresh", exchanged.refreshToken)
        server.shutdown()
    }
}

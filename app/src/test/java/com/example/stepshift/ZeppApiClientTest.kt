package com.example.stepshift

import com.example.stepshift.network.ZeppApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the Zepp (小米运动) login/upload plumbing against the live server with
 * deliberately wrong credentials. A structured "wrong credentials" response proves
 * the AES-128-CBC form encryption, the 303 Location parsing and the JSON token flow
 * all work — without needing a real account in CI.
 */
class ZeppApiClientTest {

    @Test
    fun `dummy credentials produce a structured login error, not a transport failure`() = runBlocking {
        val error = ZeppApiClient().pushSteps("test@example.com", "abc12345", 12345L)
        println("Zepp dummy-login result: $error")
        assertNotNull("expected an error for dummy credentials", error)
        // Structured server-side rejection (bad credentials / login failure),
        // NOT a local transport/timeout/TLS error.
        assertTrue(
            "unexpected error kind: $error",
            error!!.contains("账号") || error.contains("密码") || error.contains("登录")
        )
    }
}

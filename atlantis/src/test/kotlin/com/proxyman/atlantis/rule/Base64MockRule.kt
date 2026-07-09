package com.proxyman.atlantis.rule

import android.util.Base64
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

/**
 * Static-mocks android.util.Base64 for plain JUnit tests that delegates to java.util.Base64 as
 * android.util.Bas64 is not mockable outside the Android framework.
 *
 * Matches on Base64.NO_WRAP specifically making sure that a regression to a different flag fails
 * the test.
 */
class Base64MockRule : TestRule {
    override fun apply(base: Statement, description: Description?): Statement {
        return object : Statement() {
            override fun evaluate() {
                Mockito.mockStatic(Base64::class.java).use { mocked ->
                    mocked.`when`<String> {
                        Base64.encodeToString(
                            any(),
                            eq(android.util.Base64.NO_WRAP),
                        )
                    }.thenAnswer { invocation ->
                        java.util.Base64.getEncoder().encodeToString(invocation.getArgument(0))
                    }

                    mocked.`when`<ByteArray> {
                        Base64.decode(
                            any<String>(),
                            eq(Base64.NO_WRAP),
                        )
                    }.thenAnswer { invocation ->
                        java.util.Base64.getDecoder().decode(invocation.getArgument<String>(0))
                    }

                    base.evaluate()
                }
            }
        }
    }
}

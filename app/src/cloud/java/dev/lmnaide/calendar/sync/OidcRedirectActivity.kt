package dev.lmnaide.calendar.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.lmnaide.calendar.MainActivity
import dev.lmnaide.calendar.container
import kotlinx.coroutines.launch

/**
 * Receives lmnaide://oidc?code=… from the Custom Tab after "Continue with". It hands the
 * one-time code to [CloudSync.finishOidc], then returns to the existing MainActivity, which
 * closes the Custom Tab above it and keeps the user on the Settings screen.
 */
class OidcRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let { code ->
            container.appScope.launch { container.cloud.finishOidc(code) }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}

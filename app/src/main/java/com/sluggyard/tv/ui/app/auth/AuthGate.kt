package com.sluggyard.tv.ui.app.auth

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.sluggyard.tv.core.sync.SupabaseSyncScheduler
import com.sluggyard.tv.core.sync.auth.SupabaseAuthGateway
import com.sluggyard.tv.core.sync.auth.SupabaseSessionState
import com.sluggyard.tv.core.sync.auth.SyncFailureKind
import com.sluggyard.tv.core.sync.auth.SyncResult
import com.sluggyard.tv.core.sync.auth.SupabaseSessionStore
import com.sluggyard.tv.ui.app.data.GuestSessionStore
import com.sluggyard.tv.ui.design.SlugYardPalette
import com.sluggyard.tv.ui.design.SlugYardTvMetrics
import com.sluggyard.tv.ui.design.SlugYardTvTheme
import kotlinx.coroutines.launch

private enum class AuthGateMode {
    Choice,
    SignIn,
    CreateAccount,
}

@Composable
fun AuthGate(
    context: Context,
    auth: SupabaseAuthGateway,
    sessions: SupabaseSessionStore,
    guestSession: GuestSessionStore,
    onAuthenticated: () -> Unit,
    forceChoice: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(AuthGateMode.Choice) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var bootstrapping by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val choiceFocusRequester = remember { FocusRequester() }
    val emailFocusRequester = remember { FocusRequester() }

    LaunchedEffect(auth, sessions, guestSession) {
        when (val state = sessions.read()) {
            is SupabaseSessionState.Active -> {
                val result = if (state.session.isExpired()) auth.refresh() else SyncResult.Success(state.session)
                when (result) {
                    is SyncResult.Success -> {
                        SupabaseSyncScheduler.requestImmediate(context)
                        onAuthenticated()
                    }
                    is SyncResult.Failure -> message = result.message()
                    SyncResult.SessionExpired -> message = "Your session expired. Sign in again to sync."
                }
            }
            SupabaseSessionState.Corrupt -> message = "The saved session could not be restored. Sign in again."
            SupabaseSessionState.SignedOut -> {
                // Guest is an ongoing usage mode, not a one-shot bypass: once someone picks
                // "Continue as Guest" we must not force them back through this screen on every
                // process restart (TV low-memory kill, reboot, app update, etc).
                if (guestSession.isGuest() && !forceChoice) onAuthenticated()
            }
        }
        bootstrapping = false
    }

    LaunchedEffect(mode, bootstrapping) {
        if (bootstrapping) return@LaunchedEffect
        if (mode == AuthGateMode.Choice) {
            choiceFocusRequester.requestFocus()
        } else {
            emailFocusRequester.requestFocus()
        }
    }

    BackHandler(enabled = mode != AuthGateMode.Choice && !busy) {
        mode = AuthGateMode.Choice
        message = null
    }

    SlugYardTvTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = SlugYardPalette.Canvas,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = SlugYardTvMetrics.ScreenHorizontalInset,
                        vertical = SlugYardTvMetrics.ScreenVerticalInset,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("SlugYard", style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
                    Text(
                        text = when (mode) {
                            AuthGateMode.Choice -> "Use an account to sync across devices, or continue locally as a guest."
                            AuthGateMode.SignIn -> "Sign in to continue with sync enabled."
                            AuthGateMode.CreateAccount -> "Create an account to sync this TV with your other devices."
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        color = SlugYardPalette.OnCanvasMuted,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (bootstrapping) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = SlugYardPalette.Accent,
                        )
                    } else if (mode == AuthGateMode.Choice) {
                        message?.let { error ->
                            Text(error, color = SlugYardPalette.Danger)
                        }
                        AuthActionButton(
                            label = "Sign In",
                            onClick = { mode = AuthGateMode.SignIn; message = null },
                            primary = true,
                            modifier = Modifier.focusRequester(choiceFocusRequester),
                        )
                        AuthActionButton(
                            label = "Create Account",
                            onClick = { mode = AuthGateMode.CreateAccount; message = null },
                            primary = false,
                        )
                        AuthActionButton(
                            label = "Continue as Guest",
                                onClick = {
                                    scope.launch {
                                    // Guest mode must not inherit an old authenticated session;
                                    // otherwise background sync can continue under the wrong account.
                                    auth.signOut()
                                    guestSession.setGuest(true)
                                    onAuthenticated()
                                    }
                            },
                            primary = false,
                        )
                    } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; message = null },
                            modifier = Modifier.fillMaxWidth().focusRequester(emailFocusRequester),
                            singleLine = true,
                            label = { Text("Email") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; message = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        message?.let { error ->
                            Text(
                                text = error,
                                color = SlugYardPalette.Danger,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            )
                        }
                        AuthActionButton(
                            label = if (busy) "Working…" else if (mode == AuthGateMode.SignIn) "Sign In" else "Create Account",
                            onClick = {
                                if (busy) return@AuthActionButton
                                scope.launch {
                                    busy = true
                                    message = null
                                    val result = if (mode == AuthGateMode.SignIn) {
                                        auth.signIn(email, password)
                                    } else {
                                        auth.signUp(email, password)
                                    }
                                    busy = false
                                    when (result) {
                                        is SyncResult.Success -> {
                                            guestSession.setGuest(false)
                                            SupabaseSyncScheduler.requestImmediate(context)
                                            onAuthenticated()
                                        }
                                        is SyncResult.Failure -> message = result.message()
                                        SyncResult.SessionExpired -> message = "Your session expired. Sign in again."
                                    }
                                }
                            },
                            primary = true,
                            enabled = !busy,
                        )
                        TextButton(
                            onClick = { mode = AuthGateMode.Choice; message = null },
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.Start),
                        ) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthActionButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlugYardTvMetrics.CardCornerRadius)
    val focusModifier = if (focused) {
        Modifier.border(SlugYardTvMetrics.FocusRingWidth, SlugYardPalette.FocusRing, shape)
    } else {
        Modifier
    }
    val colors = if (primary) {
        ButtonDefaults.buttonColors(
            containerColor = SlugYardPalette.Accent,
            contentColor = Color(0xFF181818),
            disabledContainerColor = SlugYardPalette.SurfaceElevated,
            disabledContentColor = SlugYardPalette.OnCanvasMuted,
        )
    } else {
        ButtonDefaults.outlinedButtonColors(contentColor = SlugYardPalette.OnCanvas)
    }
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .then(focusModifier)
                .onFocusChanged { focused = it.isFocused }
                .fillMaxWidth()
                .height(58.dp),
            shape = shape,
            colors = colors,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .then(focusModifier)
                .onFocusChanged { focused = it.isFocused }
                .fillMaxWidth()
                .height(58.dp),
            shape = shape,
            border = BorderStroke(1.dp, SlugYardPalette.OnCanvasMuted),
            colors = colors,
        ) {
            Text(label)
        }
    }
}

private fun SyncResult.Failure.message(): String = when (kind) {
    SyncFailureKind.InvalidInput -> "Enter an email and password."
    SyncFailureKind.Configuration -> "Account sign-in is not configured on this build."
    SyncFailureKind.Network -> "Could not reach the account service. Guest mode is still available."
    SyncFailureKind.Unauthorized -> "The email or password is not correct."
    SyncFailureKind.Forbidden -> "This account is not allowed to sign in here."
    SyncFailureKind.RateLimited -> "Too many attempts. Wait a moment and try again."
    SyncFailureKind.Server -> "The account service is unavailable. Try again later."
    SyncFailureKind.Decode -> "The account service returned an unexpected response."
    SyncFailureKind.Conflict -> "That account already exists. Try signing in instead."
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    onSignIn: (email: String, password: String) -> Unit = { _, _ -> },
    onSignUp: (email: String, password: String) -> Unit = { _, _ -> },
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    BackHandler { onBackPress() }
    
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUpMode by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .background(
                    color = NuvioTheme.colors.BackgroundElevated,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isSignUpMode) "Create Account" else "Sign In",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NuvioTheme.colors.Secondary,
                    unfocusedBorderColor = NuvioTheme.colors.Border,
                    focusedTextColor = NuvioTheme.colors.TextPrimary,
                    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                    focusedLabelColor = NuvioTheme.colors.Secondary,
                    unfocusedLabelColor = NuvioTheme.colors.TextSecondary,
                    cursorColor = NuvioTheme.colors.Secondary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isSignUpMode) ImeAction.Next else ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NuvioTheme.colors.Secondary,
                    unfocusedBorderColor = NuvioTheme.colors.Border,
                    focusedTextColor = NuvioTheme.colors.TextPrimary,
                    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                    focusedLabelColor = NuvioTheme.colors.Secondary,
                    unfocusedLabelColor = NuvioTheme.colors.TextSecondary,
                    cursorColor = NuvioTheme.colors.Secondary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xl))
            
            Button(
                onClick = {
                    if (isSignUpMode) {
                        onSignUp(email, password)
                    } else {
                        onSignIn(email, password)
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant,
                    disabledContainerColor = NuvioTheme.colors.Secondary.copy(alpha = 0.5f)
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isLoading) "Please wait..." else if (isSignUpMode) "Sign Up" else "Sign In",
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            
            Button(
                onClick = { isSignUpMode = !isSignUpMode },
                colors = ButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextSecondary,
                    focusedContentColor = NuvioTheme.colors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSignUpMode) "Already have an account? Sign In" else "New to SlugYard? Create Account",
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

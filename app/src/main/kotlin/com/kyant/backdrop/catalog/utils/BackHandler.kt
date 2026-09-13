package com.kyant.backdrop.catalog.utils

import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler

@Composable
fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit
) {
    BackHandler(enabled, onBack)
}

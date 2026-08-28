package app.muxtv.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.muxtv.designsystem.TvTokens

/**
 * Stable destination-content reservation for the TV navigation shell.
 *
 * The rail may expand visually while it owns focus, but destination constraints
 * stay anchored to the collapsed rail width so focus movement never reflows a route.
 */
internal fun railContentReservation(railVisible: Boolean): Dp =
    if (railVisible) TvTokens.Size.railCollapsed else 0.dp

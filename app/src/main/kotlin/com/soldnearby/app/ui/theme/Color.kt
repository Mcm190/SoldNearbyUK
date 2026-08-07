package com.soldnearby.app.ui.theme

import androidx.compose.ui.graphics.Color

val BrandGreen = Color(0xFF145A32)
val BrandGreenLight = Color(0xFF1E8449)
val Amber = Color(0xFFB7950B)

/** Marker color for a point that represents more than one address (a block of flats sharing
 *  one ONSPD postcode coordinate) — deliberately not just a bigger dot, since color-only or
 *  size-only cues each fail a different kind of viewer. */
val MultiSaleRed = Color(0xFFC0392B)

/** The little "you are here" dot — a different hue from both sold-price dot colors so it
 *  never reads as another sale. */
val LocationBlue = Color(0xFF1A73E8)

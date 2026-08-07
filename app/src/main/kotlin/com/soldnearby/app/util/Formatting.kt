package com.soldnearby.app.util

import com.soldnearby.app.data.SoldProperty
import java.text.NumberFormat
import java.util.Locale

/** Land Registry Price Paid Data stores whole pounds, not pence. */
fun formatGbp(pounds: Long): String =
    NumberFormat.getCurrencyInstance(Locale.UK).format(pounds)

/** Land Registry property type codes: D/S/T/F/O. */
fun SoldProperty.propertyTypeLabel(): String = when (propertyType) {
    "D" -> "Detached"
    "S" -> "Semi-detached"
    "T" -> "Terraced"
    "F" -> "Flat / maisonette"
    else -> "Other"
}

fun SoldProperty.tenureLabel(): String = when (tenure) {
    "F" -> "Freehold"
    "L" -> "Leasehold"
    else -> tenure
}

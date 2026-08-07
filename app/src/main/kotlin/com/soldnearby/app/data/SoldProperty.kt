package com.soldnearby.app.data

/**
 * One HM Land Registry Price Paid Data transaction, geocoded to a point.
 * See /tools/build_seed_data.py for how the bundled database is produced.
 */
data class SoldProperty(
    val id: Long,
    val address: String,
    val postcode: String,
    val priceGbp: Long,
    val dateOfTransfer: String,
    val propertyType: String,
    val newBuild: Boolean,
    val tenure: String,
    val latitude: Double,
    val longitude: Double
)

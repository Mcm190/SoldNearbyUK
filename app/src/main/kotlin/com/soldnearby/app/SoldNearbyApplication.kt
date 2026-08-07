package com.soldnearby.app

import android.app.Application
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class SoldNearbyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No API key needed: the map style comes from OpenFreeMap (see MapScreen.kt),
        // a free public vector tile service, rather than a hosted MapTiler/Mapbox style.
        MapLibre.getInstance(this, null, WellKnownTileServer.MapLibre)
    }
}

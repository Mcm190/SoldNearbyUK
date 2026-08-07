// A Play Asset Delivery install-time asset pack — not a regular app module. Exists solely to
// carry seed_prices.db/.version outside the base module's assets. Google Play caps a base
// module's compressed download at 200MB; this app's bundled dataset alone is currently ~730MB
// compressed, well past that on its own, so it has to ship as a separate asset pack instead of
// a plain app/src/main/assets file. "install-time" delivery means it's downloaded and installed
// together with the app (not on-demand or fast-follow) — the data has to be there before the
// very first launch, since PriceDatabase.open() reads it synchronously on first use. Assets in
// an install-time pack are exposed through the same Context.getAssets() as base-module assets
// (that's the point of "install-time" — it behaves like part of the app from first launch), so
// PriceDatabase.kt's context.assets.open(ASSET_NAME) needed no code changes for this move.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("seed_data")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

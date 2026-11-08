package org.renpy.android;

/**
 * Ramen runs one imported game directly from app-private storage.
 * Play Asset Delivery packs and in-app billing are deliberately disabled here.
 */
public final class Constants {
    public static final String store = "none";
    public static final String[] assetPacks = new String[0];

    private Constants() {
    }
}

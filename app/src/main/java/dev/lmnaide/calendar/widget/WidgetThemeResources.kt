package dev.lmnaide.calendar.widget

import dev.lmnaide.calendar.R
import dev.lmnaide.calendar.ui.theme.AppTheme

// Rounded background fallbacks for RemoteViews on Android 8–11, where tint actions are unavailable.
internal val widgetThemeResources = mapOf(
    (AppTheme.JET_BLACK to false) to intArrayOf(R.drawable.widget_theme_jet_black_background, R.drawable.widget_theme_jet_black_add, R.drawable.widget_theme_jet_black_today),
    (AppTheme.JET_BLACK to true) to intArrayOf(R.drawable.widget_theme_jet_black_background, R.drawable.widget_theme_jet_black_add, R.drawable.widget_theme_jet_black_today),
    (AppTheme.LEMONADE to false) to intArrayOf(R.drawable.widget_theme_lemonade_light_background, R.drawable.widget_theme_lemonade_light_add, R.drawable.widget_theme_lemonade_light_today),
    (AppTheme.LEMONADE to true) to intArrayOf(R.drawable.widget_theme_lemonade_dark_background, R.drawable.widget_theme_lemonade_dark_add, R.drawable.widget_theme_lemonade_dark_today),
    (AppTheme.MATERIAL_YOU to false) to intArrayOf(R.drawable.widget_theme_google_light_background, R.drawable.widget_theme_google_light_add, R.drawable.widget_theme_google_light_today),
    (AppTheme.MATERIAL_YOU to true) to intArrayOf(R.drawable.widget_theme_google_dark_background, R.drawable.widget_theme_google_dark_add, R.drawable.widget_theme_google_dark_today),
    (AppTheme.BLUSH to false) to intArrayOf(R.drawable.widget_theme_blush_light_background, R.drawable.widget_theme_blush_light_add, R.drawable.widget_theme_blush_light_today),
    (AppTheme.BLUSH to true) to intArrayOf(R.drawable.widget_theme_blush_dark_background, R.drawable.widget_theme_blush_dark_add, R.drawable.widget_theme_blush_dark_today),
    (AppTheme.AMETHYST to false) to intArrayOf(R.drawable.widget_theme_amethyst_light_background, R.drawable.widget_theme_amethyst_light_add, R.drawable.widget_theme_amethyst_light_today),
    (AppTheme.AMETHYST to true) to intArrayOf(R.drawable.widget_theme_amethyst_dark_background, R.drawable.widget_theme_amethyst_dark_add, R.drawable.widget_theme_amethyst_dark_today),
    (AppTheme.FOREST to false) to intArrayOf(R.drawable.widget_theme_forest_light_background, R.drawable.widget_theme_forest_light_add, R.drawable.widget_theme_forest_light_today),
    (AppTheme.FOREST to true) to intArrayOf(R.drawable.widget_theme_forest_dark_background, R.drawable.widget_theme_forest_dark_add, R.drawable.widget_theme_forest_dark_today),
    (AppTheme.OCEAN to false) to intArrayOf(R.drawable.widget_theme_ocean_light_background, R.drawable.widget_theme_ocean_light_add, R.drawable.widget_theme_ocean_light_today),
    (AppTheme.OCEAN to true) to intArrayOf(R.drawable.widget_theme_ocean_dark_background, R.drawable.widget_theme_ocean_dark_add, R.drawable.widget_theme_ocean_dark_today),
    (AppTheme.EMBER to false) to intArrayOf(R.drawable.widget_theme_ember_light_background, R.drawable.widget_theme_ember_light_add, R.drawable.widget_theme_ember_light_today),
    (AppTheme.EMBER to true) to intArrayOf(R.drawable.widget_theme_ember_dark_background, R.drawable.widget_theme_ember_dark_add, R.drawable.widget_theme_ember_dark_today),
    (AppTheme.CHRISTMAS to false) to intArrayOf(R.drawable.widget_theme_christmas_light_background, R.drawable.widget_theme_christmas_light_add, R.drawable.widget_theme_christmas_light_today),
    (AppTheme.CHRISTMAS to true) to intArrayOf(R.drawable.widget_theme_christmas_dark_background, R.drawable.widget_theme_christmas_dark_add, R.drawable.widget_theme_christmas_dark_today),
)

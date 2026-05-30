package com.github.libretube.enums

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.libretube.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int
) {
    Home("home", R.string.startpage, R.drawable.ic_home),
    Trends("trends", R.string.trends, R.drawable.ic_trending),
    Subscriptions("subscriptions", R.string.subscriptions, R.drawable.ic_subscriptions),
    Music("music", R.string.music, R.drawable.music_flat_symbol),
    Shorts("shorts", R.string.shorts, R.drawable.ic_play_circle),
    Library("library", R.string.library, R.drawable.ic_library)
}

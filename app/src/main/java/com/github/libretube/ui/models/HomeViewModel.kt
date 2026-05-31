package com.github.libretube.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.PlaylistsHelper
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.extensions.runSafely
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.FlowHistoryBridge
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HomeViewModel : ViewModel() {
    private val hideWatched
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.HIDE_WATCHED_FROM_FEED,
            false
        )
    private val showUpcoming
        get() = PreferenceHelper.getBoolean(
            PreferenceKeys.SHOW_UPCOMING_IN_FEED,
            true
        )

    val trending: MutableLiveData<Pair<TrendingCategory, TrendsViewModel.TrendingStreams>> =
        MutableLiveData(null)
    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val bookmarks: MutableLiveData<List<PlaylistBookmark>> = MutableLiveData(null)
    val playlists: MutableLiveData<List<Playlists>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    private val sections get() = listOf(trending, feed, bookmarks, playlists, continueWatching)

    private var loadHomeJob: Job? = null

    fun loadHomeFeed(
        context: Context,
        subscriptionsViewModel: SubscriptionsViewModel,
        visibleItems: Set<String>,
        onUnusualLoadTime: () -> Unit
    ) {
        isLoading.value = true

        loadHomeJob?.cancel()
        loadHomeJob = viewModelScope.launch {
            val result = async {
                awaitAll(
                    async { if (visibleItems.contains(TRENDING)) loadTrending(context) },
                    async { if (visibleItems.contains(FEATURED)) loadFeed(subscriptionsViewModel) },
                    async { if (visibleItems.contains(BOOKMARKS)) loadBookmarks() },
                    async { if (visibleItems.contains(PLAYLISTS)) loadPlaylists() },
                    async { if (visibleItems.contains(WATCHING)) loadVideosToContinueWatching() }
                )
                loadedSuccessfully.value = sections.any { it.value != null }
                isLoading.value = false
            }

            withContext(Dispatchers.IO) {
                delay(UNUSUAL_LOAD_TIME_MS)
                if (result.isActive) {
                    onUnusualLoadTime.invoke()
                }
            }
        }
    }

    private suspend fun loadTrending(context: Context) {
        val region = PreferenceHelper.getTrendingRegion(context)
        val storedCategory = PreferenceHelper.getString(
            PreferenceKeys.TRENDING_CATEGORY,
            TrendingCategory.MUSIC.name
        )
        val category = runCatching { TrendingCategory.valueOf(storedCategory) }
            .getOrDefault(TrendingCategory.MUSIC)
            .let { selected ->
                if (selected == TrendingCategory.LIVE) {
                    PreferenceHelper.putString(PreferenceKeys.TRENDING_CATEGORY, TrendingCategory.MUSIC.name)
                    TrendingCategory.MUSIC
                } else {
                    selected
                }
            }

        runSafely(
            onSuccess = { videos ->
                trending.updateIfChanged(
                    Pair(
                        category,
                        TrendsViewModel.TrendingStreams(region, videos.homeVideosOnly())
                    )
                )
            },
            ioBlock = {
                MediaServiceRepository.instance.getTrending(region, category)
            }
        )
    }

    private suspend fun loadFeed(subscriptionsViewModel: SubscriptionsViewModel) {
        runSafely(
            onSuccess = { videos -> feed.updateIfChanged(videos) },
            ioBlock = { tryLoadFeed(subscriptionsViewModel) }
        )
    }

    private suspend fun loadBookmarks() {
        runSafely(
            onSuccess = { newBookmarks -> bookmarks.updateIfChanged(newBookmarks) },
            ioBlock = { DatabaseHolder.Database.playlistBookmarkDao().getAll() }
        )
    }

    private suspend fun loadPlaylists() {
        runSafely(
            onSuccess = { newPlaylists -> playlists.updateIfChanged(newPlaylists) },
            ioBlock = { PlaylistsHelper.getPlaylists() }
        )
    }

    private suspend fun loadVideosToContinueWatching() {
        if (!PlayerHelper.watchHistoryEnabled) return
        runSafely(
            onSuccess = { videos -> continueWatching.updateIfChanged(videos) },
            ioBlock = ::loadWatchingFromDB
        )
    }

    private suspend fun loadWatchingFromDB(): List<StreamItem> {
        val videos = (
            DatabaseHelper.getWatchHistoryPage(1, 20) +
                FlowHistoryBridge.getWatchHistoryPage(1, 20)
            ).distinctBy { it.videoId }

        return DatabaseHelper
            .filterUnwatched(videos.map { it.toStreamItem() })
            .homeVideosOnly()
    }

    private suspend fun tryLoadFeed(subscriptionsViewModel: SubscriptionsViewModel): List<StreamItem> {
        // use cached feed if available, otherwise load feed from API/database
        val subscriptionFeed = subscriptionsViewModel.videoFeed.value ?: run {
            SubscriptionHelper.getFeed(forceRefresh = false).also {
                subscriptionsViewModel.videoFeed.postValue(it)
            }
        }

        val filteredSubscriptions = DatabaseHelper
            .filterByStreamTypeAndWatchPosition(subscriptionFeed, hideWatched, showUpcoming)
            .homeVideosOnly()
        val relatedFeed = loadPersonalizedRelatedFeed()

        return (relatedFeed + filteredSubscriptions)
            .distinctBy { it.url.orEmpty() }
            .take(HOME_FEED_LIMIT)
    }

    private suspend fun loadPersonalizedRelatedFeed(): List<StreamItem> {
        val seeds = (
            DatabaseHelper.getWatchHistoryPage(1, RELATED_SEED_LIMIT) +
                FlowHistoryBridge.getWatchHistoryPage(1, RELATED_SEED_LIMIT)
            )
            .distinctBy { it.videoId }
            .filter { !it.isLive && !it.isShort && it.videoId.isNotBlank() }
            .take(RELATED_SEED_LIMIT)

        if (seeds.isEmpty()) return emptyList()

        return supervisorScope {
            seeds.map { seed ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(RELATED_REQUEST_TIMEOUT_MS) {
                        runCatching {
                            MediaServiceRepository.instance
                                .getStreams(seed.videoId)
                                .relatedStreams
                                .homeVideosOnly()
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll()
                .flatten()
                .distinctBy { it.url.orEmpty() }
                .filter { item -> seeds.none { it.videoId == item.url.orEmpty() } }
                .take(HOME_FEED_LIMIT)
        }
    }

    private fun List<StreamItem>.homeVideosOnly(): List<StreamItem> = filter { item ->
        val isStream = item.type == null || item.type == StreamItem.TYPE_STREAM
        isStream && !item.isLive && !item.isUpcoming && !item.isShort && !item.title.isNullOrBlank()
    }

    companion object {
        private const val UNUSUAL_LOAD_TIME_MS = 10000L
        private const val HOME_FEED_LIMIT = 40
        private const val RELATED_SEED_LIMIT = 6
        private const val RELATED_REQUEST_TIMEOUT_MS = 6000L
        private const val FEATURED = "featured"
        private const val WATCHING = "watching"
        private const val TRENDING = "trending"
        private const val BOOKMARKS = "bookmarks"
        private const val PLAYLISTS = "playlists"
    }
}

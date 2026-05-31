package com.github.libretube.ui.models

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.runSafely
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.FlowHistoryBridge
import com.github.libretube.helpers.PlayerHelper
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
    val feed: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val continueWatching: MutableLiveData<List<StreamItem>> = MutableLiveData(null)
    val isLoading: MutableLiveData<Boolean> = MutableLiveData(true)
    val loadedSuccessfully: MutableLiveData<Boolean> = MutableLiveData(false)

    private val sections get() = listOf(feed, continueWatching)

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
                    async { loadFeed() },
                    async { loadVideosToContinueWatching() }
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

    private suspend fun loadFeed() {
        runSafely(
            onSuccess = { videos -> feed.updateIfChanged(videos) },
            ioBlock = { tryLoadFeed() }
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

    private suspend fun tryLoadFeed(): List<StreamItem> {
        return loadPersonalizedRelatedFeed()
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
    }
}

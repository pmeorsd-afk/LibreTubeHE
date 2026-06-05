package com.github.libretube.api

import com.github.libretube.api.obj.Channel
import com.github.libretube.api.obj.ChannelTabResponse
import com.github.libretube.api.obj.CommentsPage
import com.github.libretube.api.obj.ContentItem
import com.github.libretube.api.obj.DeArrowContent
import com.github.libretube.api.obj.Playlist
import com.github.libretube.api.obj.SearchResult
import com.github.libretube.api.obj.SegmentData
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import io.github.aedev.flow.kosher.KosherContentFilter

class KosherMediaServiceRepository(
    private val delegate: MediaServiceRepository
) : MediaServiceRepository {
    override fun getTrendingCategories(): List<TrendingCategory> =
        delegate.getTrendingCategories()

    override suspend fun getTrending(region: String, category: TrendingCategory): List<StreamItem> =
        delegate.getTrending(region, category).filterApprovedStreams()

    override suspend fun getStreams(videoId: String): Streams {
        val streams = delegate.getStreams(videoId)
        if (!KosherContentFilter.isAllowedChannel(streams.uploaderUrl)) {
            throw IllegalStateException("התוכן אינו נמצא ברשימת הערוצים המאושרים")
        }
        streams.relatedStreams = streams.relatedStreams.filterApprovedStreams()
        return streams
    }

    override suspend fun getComments(videoId: String): CommentsPage =
        delegate.getComments(videoId)

    override suspend fun getSegments(
        videoId: String,
        category: List<String>,
        actionType: List<String>?
    ): SegmentData = delegate.getSegments(videoId, category, actionType)

    override suspend fun getDeArrowContent(videoId: String): DeArrowContent? =
        delegate.getDeArrowContent(videoId)

    override suspend fun getCommentsNextPage(videoId: String, nextPage: String): CommentsPage =
        delegate.getCommentsNextPage(videoId, nextPage)

    override suspend fun getSearchResults(searchQuery: String, filter: String): SearchResult =
        delegate.getSearchResults(searchQuery, filter).filterApprovedResults()

    override suspend fun getSearchResultsNextPage(
        searchQuery: String,
        filter: String,
        nextPage: String
    ): SearchResult = delegate.getSearchResultsNextPage(searchQuery, filter, nextPage)
        .filterApprovedResults()

    override suspend fun getSuggestions(query: String): List<String> =
        delegate.getSuggestions(query)

    override suspend fun getChannel(channelId: String): Channel {
        if (!KosherContentFilter.isAllowedChannel(channelId)) {
            throw IllegalStateException("הערוץ אינו נמצא ברשימת הערוצים המאושרים")
        }
        return delegate.getChannel(channelId).filterApprovedChannel()
    }

    override suspend fun getChannelTab(data: String, nextPage: String?): ChannelTabResponse =
        delegate.getChannelTab(data, nextPage).filterApprovedChannelTab()

    override suspend fun getChannelByName(channelName: String): Channel =
        delegate.getChannelByName(channelName).filterApprovedChannel()

    override suspend fun getChannelNextPage(channelId: String, nextPage: String): Channel {
        if (!KosherContentFilter.isAllowedChannel(channelId)) {
            throw IllegalStateException("הערוץ אינו נמצא ברשימת הערוצים המאושרים")
        }
        return delegate.getChannelNextPage(channelId, nextPage).filterApprovedChannel()
    }

    override suspend fun getPlaylist(playlistId: String): Playlist =
        delegate.getPlaylist(playlistId).filterApprovedPlaylist()

    override suspend fun getPlaylistNextPage(playlistId: String, nextPage: String): Playlist =
        delegate.getPlaylistNextPage(playlistId, nextPage).filterApprovedPlaylist()

    private suspend fun List<StreamItem>.filterApprovedStreams(): List<StreamItem> =
        filter { item -> KosherContentFilter.isAllowedChannel(item.uploaderUrl) }

    private suspend fun SearchResult.filterApprovedResults(): SearchResult =
        copy(items = items.filterApprovedContent())

    private suspend fun List<ContentItem>.filterApprovedContent(): List<ContentItem> =
        filter { item ->
            when (item.type) {
                StreamItem.TYPE_STREAM -> KosherContentFilter.isAllowedChannel(item.uploaderUrl)
                StreamItem.TYPE_CHANNEL -> KosherContentFilter.isAllowedChannel(item.url)
                StreamItem.TYPE_PLAYLIST -> KosherContentFilter.isAllowedChannel(item.uploaderUrl)
                else -> false
            }
        }

    private suspend fun Channel.filterApprovedChannel(): Channel =
        also { relatedStreams = relatedStreams.filterApprovedStreams() }

    private suspend fun ChannelTabResponse.filterApprovedChannelTab(): ChannelTabResponse =
        copy(content = content.filterApprovedContent())

    private suspend fun Playlist.filterApprovedPlaylist(): Playlist =
        copy(relatedStreams = relatedStreams.filterApprovedStreams())
}

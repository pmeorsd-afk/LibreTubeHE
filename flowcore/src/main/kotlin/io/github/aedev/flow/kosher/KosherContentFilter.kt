package io.github.aedev.flow.kosher

import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.ArtistItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.ui.screens.music.ArtistDetails
import io.github.aedev.flow.ui.screens.music.MusicPlaylist
import io.github.aedev.flow.ui.screens.music.MusicTrack
import io.github.aedev.flow.ui.screens.music.PlaylistDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

object KosherContentFilter {
    private const val REMOTE_ALLOWLIST_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/LibreTubeHE/master/kosher-allowlist.json"
    private const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val mutex = Mutex()

    @Volatile
    private var allowedKeys = emptySet<String>()

    @Volatile
    private var lastRefreshMs = 0L

    suspend fun refreshIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < REFRESH_INTERVAL_MS) return

        mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && lockedNow - lastRefreshMs < REFRESH_INTERVAL_MS) return

            val remote = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(REMOTE_ALLOWLIST_URL)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        response.body?.string()?.let { json.decodeFromString<KosherAllowlist>(it) }
                    }
                }.getOrNull()
            }

            allowedKeys = (remote ?: fallbackAllowlist).normalizedKeys()
            lastRefreshMs = lockedNow
        }
    }

    suspend fun isAllowedChannel(channelRef: String?): Boolean {
        refreshIfNeeded()
        val key = normalizeChannelKey(channelRef) ?: return false
        return key in allowedKeys
    }

    suspend fun isAllowedVideo(video: Video): Boolean = isAllowedChannel(video.channelId)

    suspend fun isAllowedShort(short: ShortVideo): Boolean = isAllowedChannel(short.channelId)

    suspend fun isAllowedTrack(track: MusicTrack): Boolean = isAllowedChannel(track.channelId)

    suspend fun isAllowedArtist(artist: ArtistDetails): Boolean =
        isAllowedChannel(artist.channelId)

    suspend fun isAllowedPlaylist(playlist: MusicPlaylist): Boolean =
        isAllowedChannel(playlist.authorId)

    suspend fun filterVideos(videos: List<Video>): List<Video> =
        videos.filter { isAllowedVideo(it) }

    suspend fun filterShorts(shorts: List<ShortVideo>): List<ShortVideo> =
        shorts.filter { isAllowedShort(it) }

    suspend fun filterTracks(tracks: List<MusicTrack>): List<MusicTrack> =
        tracks.filter { isAllowedTrack(it) }

    suspend fun filterArtists(artists: List<ArtistDetails>): List<ArtistDetails> =
        artists.filter { isAllowedArtist(it) }

    suspend fun filterPlaylists(playlists: List<MusicPlaylist>): List<MusicPlaylist> =
        playlists.filter { isAllowedPlaylist(it) }

    suspend fun filterPlaylistDetails(details: PlaylistDetails?): PlaylistDetails? {
        if (details == null) return null
        val filteredTracks = filterTracks(details.tracks)
        if (filteredTracks.isEmpty() && details.tracks.isNotEmpty()) return null
        return details.copy(
            tracks = filteredTracks,
            trackCount = filteredTracks.size
        )
    }

    suspend fun filterYTItems(items: List<YTItem>): List<YTItem> =
        items.filter { item ->
            when (item) {
                is SongItem -> item.artists.any { isAllowedChannel(it.id) }
                is ArtistItem -> isAllowedChannel(item.channelId ?: item.id)
                is PlaylistItem -> isAllowedChannel(item.author?.id)
                is AlbumItem -> item.artists.orEmpty().any { isAllowedChannel(it.id) }
            }
        }

    fun normalizeChannelKey(channelRef: String?): String? {
        val raw = channelRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutQuery = raw.substringBefore("?").trimEnd('/')

        val key = when {
            withoutQuery.contains("/channel/") ->
                withoutQuery.substringAfter("/channel/").substringBefore("/")

            withoutQuery.contains("/@") ->
                "@" + withoutQuery.substringAfter("/@").substringBefore("/")

            withoutQuery.startsWith("@") -> withoutQuery

            withoutQuery.startsWith("UC") -> withoutQuery

            else -> withoutQuery.substringAfterLast("/").takeIf { it.isNotBlank() }
        } ?: return null

        return key.trim().trimEnd('/').lowercase()
    }

    private fun KosherAllowlist.normalizedKeys(): Set<String> =
        channels.flatMap { channel ->
            listOfNotNull(
                normalizeChannelKey(channel.channelId),
                normalizeChannelKey(channel.handle),
                normalizeChannelKey(channel.url)
            )
        }.toSet()

    @Serializable
    data class KosherAllowlist(
        val channels: List<KosherChannel> = emptyList()
    )

    @Serializable
    data class KosherChannel(
        val name: String? = null,
        val url: String? = null,
        val channelId: String? = null,
        val handle: String? = null
    )

    private val fallbackAllowlist = KosherAllowlist(
        channels = listOf(
            KosherChannel(
                name = "הרב פינחס אליהו אבוחצירא שליט\"א",
                url = "https://www.youtube.com/@toraweek",
                handle = "@toraweek"
            ),
            KosherChannel(
                name = "הרב בנימין חותה שליט\"א - הערוץ הרישמי",
                url = "https://www.youtube.com/@הרבבנימיןחותהשליטא-הערוץהרישמי",
                handle = "@הרבבנימיןחותהשליטא-הערוץהרישמי"
            ),
            KosherChannel(
                name = "שיעורי הינוקא - The Yanuka Rav Shlomo Yehuda",
                url = "https://www.youtube.com/@TheYanukaRavShlomoYehuda",
                handle = "@TheYanukaRavShlomoYehuda",
                channelId = "UC2G7zKbsBNpoVYbwb-NS56w"
            ),
            KosherChannel(url = "https://www.youtube.com/channel/UCMVMTCJnx00WapqLGJle_dA", channelId = "UCMVMTCJnx00WapqLGJle_dA"),
            KosherChannel(url = "https://www.youtube.com/channel/UC-iTQfrv8pVLjlErZquyJ6Q", channelId = "UC-iTQfrv8pVLjlErZquyJ6Q"),
            KosherChannel(url = "https://www.youtube.com/channel/UCrr0YNlKrezZQ9_PaWcu_zQ", channelId = "UCrr0YNlKrezZQ9_PaWcu_zQ"),
            KosherChannel(url = "https://www.youtube.com/channel/UCWBO6JcrL1q5oz-AgtPViyA", channelId = "UCWBO6JcrL1q5oz-AgtPViyA")
        )
    )
}

package com.github.libretube.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navArgument
import com.github.libretube.R
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.screens.music.ArtistItemsScreen
import io.github.aedev.flow.ui.screens.music.ArtistPage
import io.github.aedev.flow.ui.screens.music.EnhancedMusicPlayerScreen
import io.github.aedev.flow.ui.screens.music.EnhancedMusicScreen
import io.github.aedev.flow.ui.screens.music.MoodsAndGenresScreen
import io.github.aedev.flow.ui.screens.music.MusicArtist
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.screens.music.MusicPlaylistsViewModel
import io.github.aedev.flow.ui.screens.music.MusicTrack
import io.github.aedev.flow.ui.screens.music.MusicViewModel
import io.github.aedev.flow.ui.screens.music.MusicSearchScreen
import io.github.aedev.flow.ui.screens.music.PlaylistPage
import io.github.aedev.flow.ui.screens.music.YouTubeBrowseScreen
import io.github.aedev.flow.ui.screens.shorts.ShortsScreen
import io.github.aedev.flow.ui.theme.FlowTheme
import io.github.aedev.flow.ui.theme.ThemeMode

abstract class FlowChromeFragment : Fragment() {
    private var appBar: View? = null

    override fun onResume() {
        super.onResume()
        appBar = activity?.findViewById(R.id.appBarLayout)
        appBar?.visibility = View.GONE
    }

    override fun onPause() {
        appBar?.visibility = View.VISIBLE
        super.onPause()
    }

    protected fun navigateHome() {
        runCatching { findNavController().navigate(R.id.homeFragment) }
    }

    protected fun openSettings() {
        runCatching {
            startActivity(Intent(requireContext(), com.github.libretube.ui.activities.SettingsActivity::class.java))
        }
    }
}

private object FlowMusicRoutes {
    const val HOME = "music"
    const val SEARCH = "musicSearch"
    const val MOODS = "moodsAndGenres"
    const val BROWSE = "youtube_browse/{browseId}?params={params}"
    const val ARTIST = "artist/{channelId}"
    const val ARTIST_ITEMS = "artistItems/{channelId}/{browseId}?params={params}"
    const val PLAYLIST = "musicPlaylist/{playlistId}"
    const val PLAYER = "musicPlayer/{trackId}?title={title}&artist={artist}&thumbnailUrl={thumbnailUrl}"
}

private fun browseRoute(browseId: String, params: String?): String {
    val base = "youtube_browse/${Uri.encode(browseId)}"
    return if (params.isNullOrBlank()) base else "$base?params=${Uri.encode(params)}"
}

private fun artistRoute(channelId: String) = "artist/${Uri.encode(channelId)}"

private fun playlistRoute(playlistId: String) = "musicPlaylist/${Uri.encode(playlistId)}"

private fun artistItemsRoute(channelId: String, browseId: String, params: String?): String {
    val base = "artistItems/${Uri.encode(channelId)}/${Uri.encode(browseId)}"
    return if (params.isNullOrBlank()) base else "$base?params=${Uri.encode(params)}"
}

private fun playerRoute(track: MusicTrack): String {
    return "musicPlayer/${Uri.encode(track.videoId)}" +
        "?title=${Uri.encode(track.title)}" +
        "&artist=${Uri.encode(track.artist)}" +
        "&thumbnailUrl=${Uri.encode(track.thumbnailUrl)}"
}

private fun SongItem.toMusicTrack() = MusicTrack(
    videoId = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    thumbnailUrl = thumbnail,
    duration = duration ?: 0,
    album = album?.name ?: "",
    channelId = artists.firstOrNull()?.id ?: "",
    artists = artists.map { MusicArtist(name = it.name, id = it.id) }
)

private fun NavController.openPlayerFor(track: MusicTrack) {
    navigate(playerRoute(track))
}

private fun NavController.goMusicHome() {
    navigate(FlowMusicRoutes.HOME) {
        popUpTo(FlowMusicRoutes.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

@AndroidEntryPoint
class MusicFragment : FlowChromeFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val context = requireContext()
            val navController = rememberNavController()

            LaunchedEffect(Unit) {
                EnhancedMusicPlayerManager.initialize(context)
            }

            BackHandler {
                if (!navController.popBackStack()) {
                    navigateHome()
                }
            }

            FlowTheme(themeMode = ThemeMode.DARK) {
                NavHost(
                    navController = navController,
                    startDestination = FlowMusicRoutes.HOME
                ) {
                    composable(FlowMusicRoutes.HOME) {
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()

                        EnhancedMusicScreen(
                            onBackClick = { navController.goMusicHome() },
                            onSongClick = { track, queue, source ->
                                musicPlayerViewModel.loadAndPlayTrack(track, queue, source)
                                navController.openPlayerFor(track)
                            },
                            onVideoClick = { track ->
                                NavigationHelper.navigateVideo(
                                    context,
                                    PlayerData(track.videoId),
                                    forceVideo = true
                                )
                            },
                            onArtistClick = { channelId ->
                                navController.navigate(artistRoute(channelId))
                            },
                            onSearchClick = {
                                navController.navigate(FlowMusicRoutes.SEARCH)
                            },
                            onSettingsClick = {
                                openSettings()
                            },
                            onAlbumClick = { albumId ->
                                navController.navigate(playlistRoute(albumId))
                            },
                            onMoodsClick = { item ->
                                if (item == null) {
                                    navController.navigate(FlowMusicRoutes.MOODS)
                                } else {
                                    navController.navigate(browseRoute(item.endpoint.browseId, item.endpoint.params))
                                }
                            }
                        )
                    }

                    composable(FlowMusicRoutes.MOODS) {
                        MoodsAndGenresScreen(
                            onBackClick = { navController.popBackStack() },
                            onGenreClick = { item ->
                                navController.navigate(browseRoute(item.endpoint.browseId, item.endpoint.params))
                            }
                        )
                    }

                    composable(FlowMusicRoutes.SEARCH) {
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()

                        MusicSearchScreen(
                            onBackClick = { navController.popBackStack() },
                            onTrackClick = { track, queue, source ->
                                musicPlayerViewModel.loadAndPlayTrack(track, queue, source)
                                navController.openPlayerFor(track)
                            },
                            onAlbumClick = { albumId ->
                                navController.navigate(playlistRoute(albumId))
                            },
                            onArtistClick = { channelId ->
                                navController.navigate(artistRoute(channelId))
                            },
                            onPlaylistClick = { playlistId ->
                                navController.navigate(playlistRoute(playlistId))
                            }
                        )
                    }

                    composable(
                        route = FlowMusicRoutes.BROWSE,
                        arguments = listOf(
                            navArgument("browseId") { type = NavType.StringType },
                            navArgument("params") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) {
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()

                        YouTubeBrowseScreen(
                            onBackClick = { navController.popBackStack() },
                            onSongClick = { song ->
                                val track = song.toMusicTrack()
                                musicPlayerViewModel.loadAndPlayTrack(track, listOf(track))
                                navController.openPlayerFor(track)
                            },
                            onAlbumClick = { albumId ->
                                navController.navigate(playlistRoute(albumId))
                            },
                            onArtistClick = { channelId ->
                                navController.navigate(artistRoute(channelId))
                            },
                            onPlaylistClick = { playlistId ->
                                navController.navigate(playlistRoute(playlistId))
                            }
                        )
                    }

                    composable(
                        route = FlowMusicRoutes.ARTIST,
                        arguments = listOf(navArgument("channelId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val channelId = backStackEntry.arguments?.getString("channelId").orEmpty()
                        val musicViewModel: MusicViewModel = hiltViewModel()
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()
                        val uiState by musicViewModel.uiState.collectAsState()

                        LaunchedEffect(channelId) {
                            musicViewModel.fetchArtistDetails(channelId)
                        }

                        if (uiState.isArtistLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            uiState.artistDetails?.let { details ->
                                ArtistPage(
                                    artistDetails = details,
                                    downloadedTrackIds = uiState.downloadedTrackIds,
                                    onBackClick = { navController.popBackStack() },
                                    onTrackClick = { track, queue ->
                                        musicPlayerViewModel.loadAndPlayTrack(track, queue)
                                        navController.openPlayerFor(track)
                                    },
                                    onAlbumClick = { album ->
                                        navController.navigate(playlistRoute(album.id))
                                    },
                                    onArtistClick = { id ->
                                        navController.navigate(artistRoute(id))
                                    },
                                    onFollowClick = {
                                        musicViewModel.toggleFollowArtist(details)
                                    },
                                    onSeeAllClick = { browseId, params ->
                                        navController.navigate(artistItemsRoute(channelId, browseId, params))
                                    }
                                )
                            }
                        }
                    }

                    composable(
                        route = FlowMusicRoutes.ARTIST_ITEMS,
                        arguments = listOf(
                            navArgument("channelId") { type = NavType.StringType },
                            navArgument("browseId") { type = NavType.StringType },
                            navArgument("params") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val channelId = backStackEntry.arguments?.getString("channelId").orEmpty()
                        val browseId = backStackEntry.arguments?.getString("browseId").orEmpty()
                        val params = backStackEntry.arguments?.getString("params")
                        val musicViewModel: MusicViewModel = hiltViewModel()
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()

                        ArtistItemsScreen(
                            browseId = browseId,
                            params = params,
                            onBackClick = { navController.popBackStack() },
                            viewModel = musicViewModel,
                            onTrackClick = { song ->
                                val track = song.toMusicTrack()
                                musicPlayerViewModel.loadAndPlayTrack(track, listOf(track))
                                navController.openPlayerFor(track)
                            },
                            onAlbumClick = { albumId ->
                                navController.navigate(playlistRoute(albumId))
                            },
                            onArtistClick = { id ->
                                navController.navigate(artistRoute(id))
                            },
                            onPlaylistClick = { playlistId ->
                                navController.navigate(playlistRoute(playlistId))
                            }
                        )
                    }

                    composable(
                        route = FlowMusicRoutes.PLAYLIST,
                        arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val playlistId = backStackEntry.arguments?.getString("playlistId").orEmpty()
                        val musicViewModel: MusicViewModel = hiltViewModel()
                        val musicPlayerViewModel: MusicPlayerViewModel = hiltViewModel()
                        val musicPlaylistsViewModel: MusicPlaylistsViewModel = hiltViewModel()
                        val uiState by musicViewModel.uiState.collectAsState()
                        val isSaved by musicPlaylistsViewModel.isSavedPlaylist.collectAsState()
                        val isUserPlaylist = playlistId.matches(
                            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        )

                        LaunchedEffect(playlistId) {
                            if (playlistId.startsWith("community_")) {
                                musicViewModel.loadCommunityPlaylist(playlistId.substringAfter("community_"))
                            } else {
                                musicViewModel.fetchPlaylistDetails(playlistId)
                            }
                        }

                        LaunchedEffect(playlistId, isUserPlaylist) {
                            if (!isUserPlaylist) {
                                musicPlaylistsViewModel.checkIfPlaylistSaved(playlistId)
                            }
                        }

                        if (uiState.isPlaylistLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            uiState.playlistDetails?.let { details ->
                                PlaylistPage(
                                    playlistDetails = details,
                                    onBackClick = { navController.popBackStack() },
                                    onTrackClick = { track, queue ->
                                        musicPlayerViewModel.loadAndPlayTrack(track, queue)
                                        navController.openPlayerFor(track)
                                    },
                                    onArtistClick = { channelId ->
                                        navController.navigate(artistRoute(channelId))
                                    },
                                    onLoadMore = { musicViewModel.loadMorePlaylistTracks() },
                                    isUserPlaylist = isUserPlaylist,
                                    isSaved = isSaved,
                                    onSaveToggle = {
                                        if (isSaved) {
                                            musicPlaylistsViewModel.unsavePlaylistFromLibrary(details.id)
                                        } else {
                                            musicPlaylistsViewModel.savePlaylistToLibrary(details)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    composable(
                        route = FlowMusicRoutes.PLAYER,
                        arguments = listOf(
                            navArgument("trackId") { type = NavType.StringType },
                            navArgument("title") { type = NavType.StringType; defaultValue = "" },
                            navArgument("artist") { type = NavType.StringType; defaultValue = "" },
                            navArgument("thumbnailUrl") { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { backStackEntry ->
                        val currentTrack by EnhancedMusicPlayerManager.currentTrack.collectAsState()
                        val fallbackTrack = MusicTrack(
                            videoId = backStackEntry.arguments?.getString("trackId").orEmpty(),
                            title = backStackEntry.arguments?.getString("title").orEmpty(),
                            artist = backStackEntry.arguments?.getString("artist").orEmpty(),
                            thumbnailUrl = backStackEntry.arguments?.getString("thumbnailUrl").orEmpty(),
                            duration = 0
                        )
                        val track = currentTrack ?: fallbackTrack

                        EnhancedMusicPlayerScreen(
                            track = track,
                            onBackClick = { navController.goMusicHome() },
                            onArtistClick = { channelId ->
                                navController.navigate(artistRoute(channelId))
                            },
                            onAlbumClick = { albumId ->
                                navController.navigate(playlistRoute(albumId))
                            }
                        )
                    }
                }
            }
        }
    }
}

@AndroidEntryPoint
class ShortsFragment : FlowChromeFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            FlowTheme(themeMode = ThemeMode.DARK) {
                ShortsScreen(
                    onBack = { navigateHome() },
                    onChannelClick = { channelId ->
                        NavigationHelper.navigateChannel(requireContext(), channelId)
                    }
                )
            }
        }
    }
}

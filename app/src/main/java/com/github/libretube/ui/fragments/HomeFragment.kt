package com.github.libretube.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.Playlists
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys.HOME_TAB_CONTENT
import com.github.libretube.databinding.FragmentHomeBinding
import com.github.libretube.db.obj.PlaylistBookmark
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.adapters.CarouselPlaylist
import com.github.libretube.ui.adapters.CarouselPlaylistAdapter
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.models.HomeViewModel
import com.github.libretube.ui.models.SubscriptionsViewModel
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.UncontainedCarouselStrategy
import com.google.android.material.snackbar.Snackbar


class HomeFragment : Fragment(R.layout.fragment_home) {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val subscriptionsViewModel: SubscriptionsViewModel by activityViewModels()

    private val feedAdapter = VideoCardsAdapter(columnWidthDp = 250f)
    private val watchingAdapter = VideoCardsAdapter(columnWidthDp = 250f)
    private val bookmarkAdapter = CarouselPlaylistAdapter()
    private val playlistAdapter = CarouselPlaylistAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.bookmarksRV.layoutManager = CarouselLayoutManager(UncontainedCarouselStrategy())
        binding.playlistsRV.layoutManager = CarouselLayoutManager(UncontainedCarouselStrategy())

        val bookmarksSnapHelper = CarouselSnapHelper()
        bookmarksSnapHelper.attachToRecyclerView(binding.bookmarksRV)

        val playlistsSnapHelper = CarouselSnapHelper()
        playlistsSnapHelper.attachToRecyclerView(binding.playlistsRV)

        binding.featuredRV.adapter = feedAdapter
        binding.bookmarksRV.adapter = bookmarkAdapter
        binding.playlistsRV.adapter = playlistAdapter
        binding.playlistsRV.adapter?.registerAdapterDataObserver(object :
            RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                if (itemCount == 0) {
                    binding.playlistsRV.isGone = true
                    binding.playlistsTV.isGone = true
                }
            }
        })
        binding.watchingRV.adapter = watchingAdapter

        with(homeViewModel) {
            feed.observe(viewLifecycleOwner, ::showFeed)
            continueWatching.observe(viewLifecycleOwner, ::showContinueWatching)
            isLoading.observe(viewLifecycleOwner, ::updateLoading)
        }

        binding.featuredTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_subscriptionsFragment)
        }

        binding.watchingTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_watchHistoryFragment)
        }

        binding.playlistsTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_libraryFragment)
        }

        binding.bookmarksTV.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_libraryFragment)
        }

        binding.refresh.setOnRefreshListener {
            binding.refresh.isRefreshing = true
            fetchHomeFeed()
        }

        binding.trendingRegion.isGone = true
        binding.trendingCategory.isGone = true
        binding.trendingTV.isGone = true
        binding.trendingRV.isGone = true

        binding.refreshButton.setOnClickListener {
            fetchHomeFeed()
        }

        binding.changeInstance.setOnClickListener {
            redirectToIntentSettings()
        }
    }

    override fun onResume() {
        super.onResume()

        if (homeViewModel.loadedSuccessfully.value == false) {
            fetchHomeFeed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fetchHomeFeed() {
        binding.nothingHere.isGone = true
        val defaultItems = resources.getStringArray(R.array.homeTabItemsValues)
        val visibleItems = PreferenceHelper.getStringSet(HOME_TAB_CONTENT, defaultItems.toSet())

        homeViewModel.loadHomeFeed(
            context = requireContext(),
            subscriptionsViewModel = subscriptionsViewModel,
            visibleItems = visibleItems,
            onUnusualLoadTime = ::showChangeInstanceSnackBar
        )
    }

    private fun showFeed(streamItems: List<StreamItem>?) {
        if (streamItems == null) return
        if (streamItems.isEmpty()) {
            binding.featuredRV.isGone = true
            binding.featuredTV.isGone = true
            feedAdapter.submitList(emptyList())
            return
        }

        makeVisible(binding.featuredRV, binding.featuredTV)
        val feedVideos = streamItems.take(40)

        feedAdapter.submitList(feedVideos)
    }

    private fun showBookmarks(bookmarks: List<PlaylistBookmark>?) {
        if (bookmarks == null) return

        makeVisible(binding.bookmarksTV, binding.bookmarksRV)
        bookmarkAdapter.submitList(bookmarks.map { bookmark ->
            CarouselPlaylist(
                id = bookmark.playlistId,
                title = bookmark.playlistName,
                thumbnail = bookmark.thumbnailUrl
            )
        })
    }

    private fun showPlaylists(playlists: List<Playlists>?) {
        if (playlists == null) return

        makeVisible(binding.playlistsRV, binding.playlistsTV)
        playlistAdapter.submitList(playlists.map { playlist ->
            CarouselPlaylist(
                id = playlist.id!!,
                thumbnail = playlist.thumbnail,
                title = playlist.name
            )
        })
    }

    private fun showContinueWatching(unwatchedVideos: List<StreamItem>?) {
        if (unwatchedVideos == null) return
        if (unwatchedVideos.isEmpty()) {
            binding.watchingRV.isGone = true
            binding.watchingTV.isGone = true
            watchingAdapter.submitList(emptyList())
            return
        }

        makeVisible(binding.watchingRV, binding.watchingTV)
        watchingAdapter.submitList(unwatchedVideos)
    }

    private fun updateLoading(isLoading: Boolean) {
        if (isLoading) {
            showLoading()
        } else {
            hideLoading()
        }
    }

    private fun showLoading() {
        binding.progress.isVisible = !binding.refresh.isRefreshing
        binding.nothingHere.isVisible = false
        binding.scroll.alpha = 0.3f
    }

    private fun hideLoading() {
        binding.progress.isVisible = false
        binding.refresh.isRefreshing = false

        val hasContent = homeViewModel.loadedSuccessfully.value == true
        if (hasContent) {
            showContent()
        } else {
            showNothingHere()
        }
        binding.scroll.alpha = 1.0f
    }

    private fun showNothingHere() {
        binding.nothingHere.isVisible = true
        binding.scroll.isVisible = false
    }

    private fun showContent() {
        binding.nothingHere.isVisible = false
        binding.scroll.isVisible = true
    }

    private fun showChangeInstanceSnackBar() {
        val root = _binding?.root ?: return
        Snackbar
            .make(root, R.string.suggest_change_instance, Snackbar.LENGTH_LONG)
            .apply {
                setAction(R.string.change) {
                    redirectToIntentSettings()
                }
                show()
            }
    }

    private fun redirectToIntentSettings() {
        val settingsIntent = Intent(context, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.REDIRECT_KEY, SettingsActivity.REDIRECT_TO_INTENT_SETTINGS)
        }
        startActivity(settingsIntent)
    }

    private fun makeVisible(vararg views: View) {
        views.forEach { it.isVisible = true }
    }
}

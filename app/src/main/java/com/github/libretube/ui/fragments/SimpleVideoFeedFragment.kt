package com.github.libretube.ui.fragments

import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.databinding.FragmentTrendsContentBinding
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.ui.adapters.VideoCardsAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class SimpleVideoFeedFragment(
    private val feedType: FeedType
) : DynamicLayoutManagerFragment(R.layout.fragment_trends_content) {

    enum class FeedType {
        MUSIC,
        SHORTS
    }

    private var _binding: FragmentTrendsContentBinding? = null
    private val binding get() = _binding!!
    private val adapter = VideoCardsAdapter()
    private var recyclerViewState: Parcelable? = null

    override fun setLayoutManagers(gridItems: Int) {
        _binding?.recview?.layoutManager = GridLayoutManager(context, gridItems.ceilHalf())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTrendsContentBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        binding.recview.adapter = adapter
        binding.recview.layoutManager?.onRestoreInstanceState(recyclerViewState)
        binding.homeRefresh.setOnRefreshListener { loadFeed() }

        loadFeed()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.recview.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    override fun onDestroyView() {
        recyclerViewState = _binding?.recview?.layoutManager?.onSaveInstanceState()
        super.onDestroyView()
        _binding = null
    }

    private fun loadFeed() {
        binding.progressBar.isVisible = true
        binding.homeRefresh.isRefreshing = false

        viewLifecycleOwner.lifecycleScope.launch {
            val streams = withContext(Dispatchers.IO) {
                runCatching {
                    when (feedType) {
                        FeedType.MUSIC -> loadMusic()
                        FeedType.SHORTS -> loadShorts()
                    }
                }.getOrElse { emptyList() }
            }

            _binding?.let { safeBinding ->
                safeBinding.progressBar.isVisible = false
                safeBinding.homeRefresh.isRefreshing = false
                adapter.submitList(streams)

                if (streams.isEmpty()) {
                    Snackbar.make(
                        safeBinding.root,
                        R.string.no_search_result,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun loadMusic(): List<StreamItem> {
        val region = PreferenceHelper.getTrendingRegion(requireContext())
        return MediaServiceRepository.instance.getTrending(region, TrendingCategory.MUSIC)
    }

    private suspend fun loadShorts(): List<StreamItem> {
        val region = PreferenceHelper.getString(PreferenceKeys.REGION, "IL")
        val query = if (region.equals("IL", ignoreCase = true)) "#shorts Israel" else "#shorts"

        return MediaServiceRepository.instance
            .getSearchResults(query, "videos")
            .items
            .filter { it.type == StreamItem.TYPE_STREAM }
            .map { it.toStreamItem() }
            .filter { it.isShort || (it.duration ?: 0L) in 1L..90L }
            .take(40)
    }
}

class MusicFragment : SimpleVideoFeedFragment(FeedType.MUSIC)

class ShortsFragment : SimpleVideoFeedFragment(FeedType.SHORTS)

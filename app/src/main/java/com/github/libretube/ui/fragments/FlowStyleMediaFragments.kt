package com.github.libretube.ui.fragments

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.libretube.R
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.extensions.toID
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.helpers.ProxyHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FLOW_BLACK = Color.BLACK
private const val FLOW_DARK = 0xFF151515.toInt()
private const val FLOW_DARK_SELECTED = 0xFF2A2A2A.toInt()
private const val FLOW_TEXT = Color.WHITE
private const val FLOW_MUTED = 0xFFB6B6B6.toInt()
private const val FLOW_RED = 0xFFFF0033.toInt()

private fun View.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp.toFloat()
        if (strokeColor != null) setStroke(strokeDp, strokeColor)
    }

private fun TextView.flowText(
    textValue: CharSequence,
    sizeSp: Float,
    color: Int = FLOW_TEXT,
    style: Int = Typeface.NORMAL
) {
    text = textValue
    textSize = sizeSp
    setTextColor(color)
    typeface = Typeface.DEFAULT_BOLD.takeIf { style == Typeface.BOLD } ?: Typeface.DEFAULT
    maxLines = if (sizeSp >= 24f) 1 else 2
}

abstract class FlowChromeFragment : Fragment() {
    private var appBar: View? = null

    override fun onResume() {
        super.onResume()
        appBar = activity?.findViewById(R.id.appBarLayout)
        appBar?.isGone = true
    }

    override fun onPause() {
        appBar?.isVisible = true
        super.onPause()
    }

    protected fun navigateHome() {
        runCatching { findNavController().navigate(R.id.homeFragment) }
    }

    protected fun openSearch() {
        runCatching { findNavController().navigate(R.id.searchFragment) }
    }

    protected fun openSettings() {
        runCatching {
            startActivity(Intent(requireContext(), com.github.libretube.ui.activities.SettingsActivity::class.java))
        }
    }

    protected fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

class MusicFragment : FlowChromeFragment() {
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private var selectedChip = "music"

    private fun musicChips() = listOf(
        getString(R.string.flow_chip_workout) to "workout music",
        getString(R.string.flow_chip_energize) to "energizing music",
        getString(R.string.flow_chip_feel_good) to "feel good music",
        getString(R.string.flow_chip_relax) to "relaxing music",
        getString(R.string.flow_chip_commute) to "commute music"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(FLOW_BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(0), dp(96))
        }
        scroll.addView(content)
        root.addView(scroll)

        progress = ProgressBar(requireContext()).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(FLOW_RED)
        }
        root.addView(
            progress,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
        )

        renderStaticHeader()
        loadMusic()
        return root
    }

    private fun renderStaticHeader() {
        content.removeAllViews()

        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), dp(18))
        }
        header.addView(iconButton(R.drawable.ic_arrow_back, getString(R.string.back)) { navigateHome() })
        header.addView(
            TextView(requireContext()).apply {
                flowText(getString(R.string.music), 34f, FLOW_TEXT, Typeface.BOLD)
                letterSpacing = 0.08f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        )
        header.addView(iconButton(R.drawable.ic_search, getString(R.string.search_hint)) { showMusicSearch() })
        header.addView(iconButton(R.drawable.ic_settings, getString(R.string.settings)) { openSettings() })
        content.addView(header)

        val chipScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val chipRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(16), dp(12))
        }
        musicChips().forEach { (label, query) ->
            chipRow.addView(flowChip(label, query == selectedChip) {
                selectedChip = query
                renderStaticHeader()
                loadMusic()
            })
        }
        chipScroll.addView(chipRow)
        content.addView(chipScroll)
    }

    private fun flowChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            flowText(label, 15f, FLOW_TEXT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(
                if (selected) FLOW_DARK_SELECTED else FLOW_DARK,
                dp(14),
                0xFF333333.toInt()
            )
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }
    }

    private fun showMusicSearch() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.flow_music_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.flow_music_search_title)
            .setView(input)
            .setPositiveButton(R.string.search_hint) { _, _ ->
                applyMusicSearch(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
                applyMusicSearch(input.text?.toString().orEmpty())
                dialog.dismiss()
                true
            }
        }
        dialog.show()
    }

    private fun applyMusicSearch(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        selectedChip = cleanQuery
        renderStaticHeader()
        loadMusic()
    }

    private fun iconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(requireContext()).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(FLOW_TEXT)
            background = rounded(Color.TRANSPARENT, dp(24))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
    }

    private fun loadMusic() {
        progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val repository = MediaServiceRepository.instance
                val quick = async {
                    runCatching {
                        repository.getSearchResults(selectedChip, "music_songs")
                            .items
                            .mapNotNull { if (it.type == StreamItem.TYPE_STREAM) it.toStreamItem() else null }
                            .take(16)
                    }.getOrDefault(emptyList())
                }
                val recommended = async {
                    runCatching {
                        repository.getTrending(
                            PreferenceHelper.getTrendingRegion(requireContext()),
                            TrendingCategory.MUSIC
                        ).take(16)
                    }.getOrDefault(emptyList())
                }
                val recent = async {
                    runCatching {
                        repository.getSearchResults("new music videos", "music_videos")
                            .items
                            .mapNotNull { if (it.type == StreamItem.TYPE_STREAM) it.toStreamItem() else null }
                            .take(16)
                    }.getOrDefault(emptyList())
                }
                MusicScreenState(quick.await(), recommended.await(), recent.await())
            }

            if (!isAdded) return@launch
            progress.isGone = true
            renderMusicState(state)
        }
    }

    private fun renderMusicState(state: MusicScreenState) {
        while (content.childCount > 2) content.removeViewAt(2)

        if (state.quickPicks.isEmpty() && state.recommended.isEmpty() && state.recent.isEmpty()) {
            content.addView(
                TextView(requireContext()).apply {
                    flowText(getString(R.string.no_search_result), 16f, FLOW_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(48), dp(32), dp(48))
                }
            )
            return
        }

        addSpeedDial((state.recommended + state.quickPicks).distinctBy { it.url?.toID().orEmpty() })
        addQuickPicks(state.quickPicks.ifEmpty { state.recommended })
        addAlbumSection(getString(R.string.flow_recommended), state.recommended.ifEmpty { state.quickPicks })
        addAlbumSection(getString(R.string.flow_recently_played), state.recent.ifEmpty { state.recommended })
    }

    private fun addSpeedDial(items: List<StreamItem>) {
        if (items.isEmpty()) return
        content.addView(sectionTitle(getString(R.string.flow_speed_dial)))

        val scroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val pages = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(16), dp(10))
        }
        items.take(18).chunked(9).forEachIndexed { pageIndex, pageItems ->
            val grid = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(342), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12)
                }
            }
            for (rowIndex in 0 until 3) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (columnIndex in 0 until 3) {
                    val slot = rowIndex * 3 + columnIndex
                    val item = pageItems.getOrNull(slot)
                    row.addView(
                        if (pageIndex == 0 && slot == 8) speedShuffleCard(items) else speedDialCard(item, items),
                        LinearLayout.LayoutParams(0, dp(106), 1f).apply {
                            marginEnd = if (columnIndex == 2) 0 else dp(8)
                            bottomMargin = if (rowIndex == 2) 0 else dp(8)
                        }
                    )
                }
                grid.addView(row)
            }
            pages.addView(grid)
        }
        scroller.addView(pages)
        content.addView(scroller)
    }

    private fun addQuickPicks(items: List<StreamItem>) {
        if (items.isEmpty()) return
        content.addView(sectionTitle(getString(R.string.flow_quick_picks)))

        val scroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val columns = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(16), dp(8))
        }
        items.take(16).chunked(4).forEach { columnItems ->
            val column = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(315), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(18)
                }
            }
            columnItems.forEach { item ->
                column.addView(quickPickRow(item, items))
            }
            columns.addView(column)
        }
        scroller.addView(columns)
        content.addView(scroller)
    }

    private fun addAlbumSection(title: String, items: List<StreamItem>) {
        if (items.isEmpty()) return
        content.addView(sectionTitle(title))

        val scroller = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(16), dp(16))
        }
        items.take(16).forEach { item ->
            row.addView(albumCard(item, items))
        }
        scroller.addView(row)
        content.addView(scroller)
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(requireContext()).apply {
            flowText(title, 24f, FLOW_TEXT, Typeface.BOLD)
            setPadding(0, dp(18), dp(16), dp(12))
        }
    }

    private fun speedDialCard(item: StreamItem?, playlist: List<StreamItem>): FrameLayout {
        return FrameLayout(requireContext()).apply {
            background = rounded(FLOW_DARK, dp(12))
            setOnClickListener { item?.let { openItem(it, playlist) } }

            if (item != null) {
                addView(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        item.thumbnail?.let { ImageHelper.loadImage(it, this) }
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                addView(
                    View(context).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(0x22000000, 0x00000000, 0xBB000000.toInt())
                        )
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                addView(
                    TextView(context).apply {
                        flowText(item.title.orEmpty(), 14f, FLOW_TEXT, Typeface.BOLD)
                        maxLines = 1
                        setPadding(dp(10), 0, dp(10), dp(10))
                        gravity = Gravity.BOTTOM
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
        }
    }

    private fun speedShuffleCard(items: List<StreamItem>): FrameLayout {
        return FrameLayout(requireContext()).apply {
            background = rounded(FLOW_DARK_SELECTED, dp(12), FLOW_RED)
            setOnClickListener {
                val shuffled = items.shuffled()
                shuffled.firstOrNull()?.let { openItem(it, shuffled) }
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_play_circle)
                    setColorFilter(FLOW_TEXT)
                    alpha = 0.9f
                },
                FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER)
            )
        }
    }

    private fun quickPickRow(item: StreamItem, playlist: List<StreamItem>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { openItem(item, playlist) }

            addView(squareThumbnail(item, 58))
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, dp(6), 0)
                    addView(TextView(context).apply {
                        flowText(item.title.orEmpty(), 16f, FLOW_TEXT)
                        maxLines = 1
                    })
                    addView(TextView(context).apply {
                        flowText(item.uploaderName.orEmpty(), 14f, FLOW_MUTED)
                        maxLines = 1
                    })
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun albumCard(item: StreamItem, playlist: List<StreamItem>): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { openItem(item, playlist) }
            layoutParams = LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT)

            addView(squareThumbnail(item, 180))
            addView(TextView(context).apply {
                flowText(item.title.orEmpty(), 16f, FLOW_TEXT)
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(context).apply {
                flowText(item.uploaderName.orEmpty(), 14f, FLOW_MUTED)
                maxLines = 1
            })
        }
    }

    private fun squareThumbnail(item: StreamItem, sizeDp: Int): FrameLayout {
        return FrameLayout(requireContext()).apply {
            background = rounded(FLOW_DARK, dp(8))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    item.thumbnail?.let { ImageHelper.loadImage(it, this) }
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun openItem(item: StreamItem, playlist: List<StreamItem> = listOf(item)) {
        val videoId = item.url?.toID()?.takeIf { it.isNotBlank() } ?: return
        val queue = playlist
            .filter { it.url?.toID()?.isNotBlank() == true }
            .distinctBy { it.url?.toID().orEmpty() }
            .ifEmpty { listOf(item) }

        PlayingQueue.queueMode = PlayingQueueMode.ONLINE
        PlayingQueue.clear()
        PlayingQueue.setStreams(queue)
        PlayingQueue.updateCurrent(item)

        if (PlayerHelper.watchHistoryEnabled) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                DatabaseHelper.addToWatchHistory(item.toWatchHistoryItem(videoId))
            }
        }

        BackgroundHelper.playOnBackground(requireContext(), PlayerData(videoId, keepQueue = true))
        NavigationHelper.openAudioPlayerFragment(requireContext(), minimizeByDefault = false)
    }

    private data class MusicScreenState(
        val quickPicks: List<StreamItem>,
        val recommended: List<StreamItem>,
        val recent: List<StreamItem>
    )
}

class ShortsFragment : FlowChromeFragment() {
    private lateinit var pager: ViewPager2
    private lateinit var progress: ProgressBar
    private val adapter = FlowShortsAdapter()
    private var shortsPlayer: ExoPlayer? = null
    private var currentShortVideoId: String? = null
    private var currentPlayerView: PlayerView? = null
    private var playbackJob: Job? = null
    private var previousPage = 0
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            val oldPage = previousPage
            previousPage = position
            adapter.notifyItemChanged(oldPage)
            adapter.notifyItemChanged(position)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(FLOW_BLACK)
        }

        pager = ViewPager2(requireContext()).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            adapter = this@ShortsFragment.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            registerOnPageChangeCallback(pageChangeCallback)
        }
        root.addView(
            pager,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        progress = ProgressBar(requireContext()).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(FLOW_RED)
        }
        root.addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))

        loadShorts()
        return root
    }

    override fun onResume() {
        super.onResume()
        shortsPlayer?.play()
    }

    override fun onPause() {
        shortsPlayer?.pause()
        super.onPause()
    }

    override fun onDestroyView() {
        playbackJob?.cancel()
        currentPlayerView?.player = null
        shortsPlayer?.release()
        shortsPlayer = null
        currentShortVideoId = null
        currentPlayerView = null
        runCatching { pager.unregisterOnPageChangeCallback(pageChangeCallback) }
        super.onDestroyView()
    }

    private fun loadShorts() {
        progress.isVisible = true
        viewLifecycleOwner.lifecycleScope.launch {
            val shorts = withContext(Dispatchers.IO) {
                val region = PreferenceHelper.getString(PreferenceKeys.REGION, "IL")
                val queries = if (region.equals("IL", ignoreCase = true)) {
                    listOf("#shorts Israel", "shorts Israel", "viral shorts")
                } else {
                    listOf("#shorts", "viral shorts", "shorts")
                }
                queries.flatMap { query ->
                    runCatching {
                        MediaServiceRepository.instance
                            .getSearchResults(query, "videos")
                            .items
                            .mapNotNull { if (it.type == StreamItem.TYPE_STREAM) it.toStreamItem() else null }
                    }.getOrDefault(emptyList())
                }
                    .filter { it.isShort || (it.duration ?: Long.MAX_VALUE) in 1L..90L || it.url.orEmpty().contains("/shorts/") }
                    .distinctBy { it.url?.toID().orEmpty() }
                    .take(50)
            }

            if (!isAdded) return@launch
            progress.isGone = true
            adapter.submit(shorts)
            if (shorts.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_search_result, Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class FlowShortsAdapter : RecyclerView.Adapter<FlowShortsAdapter.ShortHolder>() {
        private val items = mutableListOf<StreamItem>()

        fun submit(newItems: List<StreamItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortHolder {
            return ShortHolder(createShortPage(parent))
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ShortHolder, position: Int) {
            holder.bind(items[position], position)
        }

        inner class ShortHolder(private val page: ShortPageViews) : RecyclerView.ViewHolder(page.root) {
            fun bind(item: StreamItem, position: Int) {
                item.thumbnail?.let { ImageHelper.loadImage(it, page.thumbnail) }
                page.title.text = item.title.orEmpty()
                page.channel.text = item.uploaderName.orEmpty()
                page.sound.text = getString(R.string.flow_original_sound, item.uploaderName.orEmpty().ifBlank { getString(R.string.unknown) })
                (item.uploaderAvatar ?: item.thumbnail)?.let { ImageHelper.loadImage(it, page.avatar) }
                (item.thumbnail ?: item.uploaderAvatar)?.let { ImageHelper.loadImage(it, page.record) }

                page.root.setOnClickListener { toggleShortPlayback(page) }
                page.play.setOnClickListener { toggleShortPlayback(page) }
                page.channelRow.setOnClickListener {
                    NavigationHelper.navigateChannel(requireContext(), item.uploaderUrl)
                }
                page.share.setOnClickListener { shareShort(item) }
                page.more.setOnClickListener {
                    Toast.makeText(
                        requireContext(),
                        item.shortDescription?.takeIf { description -> description.isNotBlank() } ?: item.title.orEmpty(),
                        Toast.LENGTH_LONG
                    ).show()
                }

                if (position == pager.currentItem) {
                    attachPlayerToPage(page, item)
                } else {
                    if (page.playerView == currentPlayerView) currentPlayerView = null
                    page.playerView.player = null
                    page.playerView.isGone = true
                    page.loading.isGone = true
                    page.thumbnail.isVisible = true
                    page.play.isVisible = true
                }
            }
        }
    }

    private data class ShortPageViews(
        val root: FrameLayout,
        val playerView: PlayerView,
        val thumbnail: ImageView,
        val loading: ProgressBar,
        val title: TextView,
        val channel: TextView,
        val sound: TextView,
        val avatar: ImageView,
        val record: ImageView,
        val channelRow: LinearLayout,
        val play: ImageView,
        val share: LinearLayout,
        val more: LinearLayout
    )

    private fun createShortPage(parent: ViewGroup): ShortPageViews {
        val root = FrameLayout(parent.context).apply {
            setBackgroundColor(FLOW_BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val playerView = PlayerView(parent.context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.TRANSPARENT)
            setBackgroundColor(FLOW_BLACK)
            isGone = true
        }
        root.addView(playerView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(FLOW_DARK)
            tag = "short_thumbnail"
        }
        root.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val shade = View(parent.context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x00000000, 0xCC000000.toInt())
            )
        }
        root.addView(shade, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val play = ImageView(parent.context).apply {
            setImageResource(R.drawable.ic_play_circle)
            setColorFilter(FLOW_TEXT)
            alpha = 0.88f
            contentDescription = getString(R.string.play)
            tag = "short_play"
        }
        root.addView(play, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))

        val loading = ProgressBar(parent.context).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(FLOW_RED)
            isGone = true
            tag = "short_loading"
        }
        root.addView(loading, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

        val topBack = iconButtonForShort(R.drawable.ic_arrow_back, getString(R.string.back)) { navigateHome() }
        root.addView(
            topBack,
            FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(16)
                marginStart = dp(12)
            }
        )

        val actions = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val like = shortAction(R.drawable.ic_like, getString(R.string.like))
        val comments = shortAction(R.drawable.ic_comment, getString(R.string.comments))
        val save = shortAction(R.drawable.ic_bookmark_outlined, getString(R.string.save))
        val share = shortAction(R.drawable.ic_share, getString(R.string.share))
        val more = shortAction(R.drawable.ic_more_vert, getString(R.string.tooltip_options))
        listOf(like, comments, save, share, more).forEach(actions::addView)
        val record = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(FLOW_DARK_SELECTED, dp(21))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        actions.addView(
            record,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                topMargin = dp(2)
            }
        )
        root.addView(
            actions,
            FrameLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(4)
            }
        )

        val bottom = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(96), dp(24))
        }
        val channelRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val avatar = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(FLOW_DARK_SELECTED, dp(22))
        }
        channelRow.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
        val channel = TextView(parent.context).apply {
            flowText("", 18f, FLOW_TEXT, Typeface.BOLD)
            setPadding(dp(12), 0, dp(10), 0)
        }
        channelRow.addView(channel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val subscribe = TextView(parent.context).apply {
            flowText(getString(R.string.subscribe), 13f, FLOW_TEXT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(FLOW_RED, dp(22))
        }
        channelRow.addView(subscribe)
        bottom.addView(channelRow)

        val title = TextView(parent.context).apply {
            flowText("", 17f, FLOW_TEXT)
        }
        bottom.addView(title)
        val sound = TextView(parent.context).apply {
            flowText("", 14f, FLOW_TEXT)
            setPadding(0, dp(10), 0, 0)
        }
        bottom.addView(sound)

        root.addView(
            bottom,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        return ShortPageViews(root, playerView, image, loading, title, channel, sound, avatar, record, channelRow, play, share, more)
    }

    private fun iconButtonForShort(@DrawableRes icon: Int, description: String, onClick: () -> Unit): ImageButton {
        return ImageButton(requireContext()).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(FLOW_TEXT)
            background = rounded(0x33000000, dp(28))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
        }
    }

    private fun shortAction(@DrawableRes icon: Int, label: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            val image = ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(FLOW_TEXT)
            }
            addView(image, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(TextView(context).apply {
                flowText(label, 12f, FLOW_TEXT)
                gravity = Gravity.CENTER
                maxLines = 1
            })
        }
    }

    private fun getShortPlayer(): ExoPlayer {
        return shortsPlayer ?: PlayerHelper
            .createPlayer(requireContext(), DefaultTrackSelector(requireContext()))
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val page = currentPlayerView ?: return
                        val pageRoot = page.parent as? FrameLayout ?: return
                        val loading = pageRoot.findViewWithTag<ProgressBar>("short_loading")
                        val thumbnail = pageRoot.findViewWithTag<ImageView>("short_thumbnail")
                        val play = pageRoot.findViewWithTag<ImageView>("short_play")
                        loading?.isVisible = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            thumbnail?.isGone = true
                            play?.isGone = true
                        }
                    }
                })
            }
            .also { shortsPlayer = it }
    }

    private fun attachPlayerToPage(page: ShortPageViews, item: StreamItem) {
        val videoId = item.url?.toID()?.takeIf { it.isNotBlank() } ?: return
        val player = getShortPlayer()

        if (currentPlayerView != page.playerView) {
            currentPlayerView?.player = null
            currentPlayerView = page.playerView
            page.playerView.player = player
        }

        page.playerView.isVisible = true
        page.loading.isVisible = currentShortVideoId != videoId || player.playbackState == Player.STATE_BUFFERING
        page.thumbnail.isVisible = currentShortVideoId != videoId || player.playbackState != Player.STATE_READY
        page.play.isVisible = currentShortVideoId != videoId || !player.isPlaying

        if (currentShortVideoId == videoId) {
            player.play()
            return
        }

        currentShortVideoId = videoId
        playbackJob?.cancel()
        playbackJob = viewLifecycleOwner.lifecycleScope.launch {
            val mediaItem = withContext(Dispatchers.IO) {
                runCatching {
                    val streams = MediaServiceRepository.instance.getStreams(videoId)
                    val mediaItem = createShortMediaItem(streams)
                    if (mediaItem != null && PlayerHelper.watchHistoryEnabled) {
                        DatabaseHelper.addToWatchHistory(
                            streams.toStreamItem(videoId)
                                .toWatchHistoryItem(videoId)
                                .copy(isShort = true)
                        )
                    }
                    mediaItem
                }.getOrNull()
            }

            if (!isAdded || currentShortVideoId != videoId) return@launch
            if (mediaItem == null) {
                page.loading.isGone = true
                page.play.isVisible = true
                Toast.makeText(requireContext(), R.string.unknown_error, Toast.LENGTH_SHORT).show()
                return@launch
            }

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            player.play()
        }
    }

    private fun createShortMediaItem(streams: Streams): MediaItem? {
        return when {
            streams.videoStreams.isNotEmpty() -> {
                MediaItem.Builder()
                    .setUri(PlayerHelper.createDashSource(streams, requireContext()))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
            }
            streams.hls != null -> {
                MediaItem.Builder()
                    .setUri(ProxyHelper.rewriteUrlUsingProxyPreference(streams.hls).toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            }
            else -> null
        }
    }

    private fun toggleShortPlayback(page: ShortPageViews) {
        val player = shortsPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            page.play.isVisible = true
        } else {
            player.play()
            page.play.isGone = true
        }
    }

    private fun shareShort(item: StreamItem) {
        val videoId = item.url?.toID().orEmpty()
        val shareText = "https://youtu.be/$videoId"
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, item.title)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                },
                getString(R.string.share)
            )
        )
    }
}

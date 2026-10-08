package com.myAllVideoBrowser.ui.main.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.FragmentSubtitleSearchBinding
import com.myAllVideoBrowser.databinding.ItemSubtitleSearchResultBinding
import com.myAllVideoBrowser.ui.main.base.BaseFragment
import com.myAllVideoBrowser.util.FileUtil
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Two-step subtitle search:
 *  1. search a movie/show title against TMDB (via the Wyzie proxy)
 *  2. pick one of its available subtitles and download it next to the
 *     matching local video file.
 */
class SubtitleSearchFragment : BaseFragment() {

    companion object {
        const val ARG_VIDEO_NAME = "video_base_name"

        fun newInstance(bundle: Bundle) = SubtitleSearchFragment().apply { arguments = bundle }
    }

    @Inject
    lateinit var fileUtil: FileUtil

    private lateinit var dataBinding: FragmentSubtitleSearchBinding
    private lateinit var subtitleDownloadManager: SubtitleDownloadManager

    private var videoBaseName: String = "video"

    // step state
    private var mediaResults: List<TmdbMediaResult> = emptyList()
    private var subtitleResults: List<SubtitleInfo> = emptyList()
    private var isShowingSubtitles = false

    private val resultsAdapter = ResultsAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dataBinding = FragmentSubtitleSearchBinding.inflate(inflater, container, false)
        return dataBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        subtitleDownloadManager = SubtitleDownloadManager(requireContext().applicationContext)
        videoBaseName = arguments?.getString(ARG_VIDEO_NAME) ?: "video"
        dataBinding.editQuery.setText(videoBaseName)

        dataBinding.toolbar.setNavigationOnClickListener {
            if (isShowingSubtitles) {
                showMediaResults()
            } else {
                parentFragmentManager.popBackStack()
            }
        }

        dataBinding.recyclerResults.layoutManager = LinearLayoutManager(requireContext())
        dataBinding.recyclerResults.adapter = resultsAdapter

        dataBinding.btnSearch.setOnClickListener { runMediaSearch() }

        runMediaSearch()
    }

    private fun runMediaSearch() {
        val query = dataBinding.editQuery.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) return

        isShowingSubtitles = false
        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = subtitleDownloadManager.searchMedia(query)) {
                is SubtitleDownloadManager.MediaSearchResult.Success -> {
                    mediaResults = result.media
                    setLoading(false)
                    showMediaResults()
                }

                is SubtitleDownloadManager.MediaSearchResult.Error -> {
                    setLoading(false)
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    dataBinding.textEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun runSubtitleSearch(media: TmdbMediaResult) {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = subtitleDownloadManager.searchSubtitlesByMediaId(media.id)) {
                is SubtitleDownloadManager.SubtitleSearchResult.Success -> {
                    subtitleResults = result.subtitles
                    setLoading(false)
                    showSubtitleResults()
                }

                is SubtitleDownloadManager.SubtitleSearchResult.Error -> {
                    setLoading(false)
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadSubtitle(subtitle: SubtitleInfo) {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = subtitleDownloadManager.downloadSubtitle(
                subtitle, fileUtil.folderDir, videoBaseName
            )
            setLoading(false)
            when (result) {
                is SubtitleDownloadManager.DownloadResult.Success -> {
                    Toast.makeText(
                        context,
                        getString(R.string.subtitle_downloaded_toast, result.file.name),
                        Toast.LENGTH_LONG
                    ).show()
                    parentFragmentManager.popBackStack()
                }

                is SubtitleDownloadManager.DownloadResult.Error -> {
                    Toast.makeText(
                        context,
                        getString(R.string.subtitle_download_failed_toast, result.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showMediaResults() {
        isShowingSubtitles = false
        dataBinding.textEmpty.visibility =
            if (mediaResults.isEmpty()) View.VISIBLE else View.GONE
        resultsAdapter.submit(
            mediaResults.map { m ->
                Pair(
                    "${m.title}${m.releaseYear?.let { " ($it)" } ?: ""}",
                    m.mediaType
                )
            }
        ) { index -> runSubtitleSearch(mediaResults[index]) }
    }

    private fun showSubtitleResults() {
        isShowingSubtitles = true
        dataBinding.textEmpty.visibility =
            if (subtitleResults.isEmpty()) View.VISIBLE else View.GONE
        resultsAdapter.submit(
            subtitleResults.map { s ->
                Pair(
                    s.languageDisplay ?: s.language ?: "Unknown language",
                    "${s.format?.uppercase() ?: "SRT"} · ${s.release ?: ""}"
                )
            }
        ) { index -> downloadSubtitle(subtitleResults[index]) }
    }

    private fun setLoading(loading: Boolean) {
        dataBinding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        dataBinding.recyclerResults.visibility = if (loading) View.GONE else View.VISIBLE
        if (loading) dataBinding.textEmpty.visibility = View.GONE
    }

    private class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
        private var items: List<Pair<String, String>> = emptyList()
        private var onClick: (Int) -> Unit = {}

        fun submit(newItems: List<Pair<String, String>>, onClick: (Int) -> Unit) {
            items = newItems
            this.onClick = onClick
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSubtitleSearchResultBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (title, subtitle) = items[position]
            holder.binding.textTitle.text = title
            holder.binding.textSubtitle.text = subtitle
            holder.binding.root.setOnClickListener { onClick(position) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(val binding: ItemSubtitleSearchResultBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}

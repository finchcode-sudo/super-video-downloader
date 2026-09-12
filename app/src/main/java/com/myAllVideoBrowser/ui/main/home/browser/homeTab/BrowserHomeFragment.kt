package com.myAllVideoBrowser.ui.main.home.browser.homeTab

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.databinding.FragmentBrowserHomeBinding
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.SuggestionListener
import com.myAllVideoBrowser.ui.component.adapter.TopPageAdapter
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BaseWebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserListener
import com.myAllVideoBrowser.ui.main.home.browser.TabManagerProvider
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFactory
import com.myAllVideoBrowser.util.AppUtil
import kotlinx.coroutines.launch
import javax.inject.Inject

interface BrowserHomeListener : BrowserListener {

    override fun onBrowserReloadClicked() {
    }

    override fun onTabCloseClicked() {
    }

    override fun onBrowserStopClicked() {
    }

    override fun onBrowserBackClicked() {
    }

    override fun onBrowserForwardClicked() {
    }
}

class BrowserHomeFragment : BaseWebTabFragment() {

    companion object {
        fun newInstance() = BrowserHomeFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    @Inject
    lateinit var appUtil: AppUtil

    lateinit var binding: FragmentBrowserHomeBinding

    private lateinit var openPageIProvider: TabManagerProvider

    private lateinit var homeViewModel: BrowserHomeViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var topPageAdapter: TopPageAdapter

    private lateinit var suggestionAdapter: SuggestionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mainViewModel = mainActivity.mainViewModel
        homeViewModel = ViewModelProvider(this, viewModelFactory)[BrowserHomeViewModel::class.java]
        homeViewModel.settingsModel = mainActivity.settingsViewModel
        openPageIProvider = mainActivity.mainViewModel.browserServicesProvider!!

        topPageAdapter = TopPageAdapter(requireContext(), emptyList(), itemListener)
        suggestionAdapter = SuggestionAdapter(requireContext(), emptyList(), suggestionListener)

        binding = FragmentBrowserHomeBinding.inflate(inflater, container, false).apply {
            buildWebTabMenu(this.browserHomeMenuButton, true)

            this.viewModel = homeViewModel
            this.mainVModel = mainViewModel
            this.browserMenuListener = menuListener
            this.topPagesGrid.adapter = topPageAdapter

            this.homeEtSearch.setAdapter(suggestionAdapter)
            this.homeEtSearch.addTextChangedListener(onInputHomeSearchChangeListener)
            this.homeEtSearch.setOnTouchListener(clearIconTouchListener)
            this.homeEtSearch.imeOptions = EditorInfo.IME_ACTION_DONE
            this.homeEtSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    this.homeEtSearch.clearFocus()
                    viewModel?.viewModelScope?.launch {
                        val inputText = (this@apply.homeEtSearch as EditText).text.toString()
                        this@apply.homeEtSearch.text.clear()
                        openNewTab(inputText)
                    }
                    false
                } else false
            }
            this.goButton.setOnClickListener {
                viewModel?.viewModelScope?.launch {
                    val inputText = (this@apply.homeEtSearch as EditText).text.toString()
                    this@apply.homeEtSearch.text.clear()
                    openNewTab(inputText)
                }
            }
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        handleFirstStartGuide()

        homeViewModel.start()
        val openingUrl = mainViewModel.openedUrl.get()
        val openingText = mainViewModel.openedText.get()

        if (openingUrl != null) {
            openNewTab(openingUrl)
            mainViewModel.openedUrl.set(null)
        }

        if (openingText != null) {
            openNewTab(openingText)
            mainViewModel.openedText.set(null)
        }
    }

    // Bug fix for not updating home page grid after adding new bookmark
    override fun onResume() {
        super.onResume()
        val bookmarksList = mainViewModel.bookmarksList.get()?.toMutableList()
        mainViewModel.bookmarksList.set(bookmarksList)
    }

    private val suggestionListener = object : SuggestionListener {
        override fun onItemClicked(suggestion: Suggestion) {
            openNewTab(suggestion.content)
        }
    }

    private fun openNewTab(input: String) {
        if (input.isNotEmpty()) {
            openPageIProvider.getOpenTabEvent().value = WebTabFactory.createWebTabFromInput(input)
        }
    }

    private val onInputHomeSearchChangeListener = object : TextWatcher {
        override fun afterTextChanged(s: Editable) {
            val input = s.toString()
            homeViewModel.searchTextInput.set(input)
            updateClearIcon(input.isNotEmpty())
            if (!(input.startsWith("http://") || input.startsWith("https://"))) {
                homeViewModel.showSuggestions()
            }
            homeViewModel.homePublishSubject.onNext(input)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }
    }

    /**
     * Shows a Firefox-style clear ("x") icon at the end of the search field
     * whenever it has text, hides it when empty.
     */
    private fun updateClearIcon(hasText: Boolean) {
        val editText = binding.homeEtSearch
        val startDrawable = editText.compoundDrawablesRelative[0]
        val endDrawable = if (hasText) {
            ContextCompat.getDrawable(requireContext(), R.drawable.close_24px)
        } else null
        editText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            startDrawable, null, endDrawable, null
        )
    }

    /**
     * Detects taps on the clear ("x") compound drawable and empties the
     * field, since compound drawables aren't clickable on their own.
     */
    private val clearIconTouchListener = View.OnTouchListener { v, event ->
        val editText = v as EditText
        if (event.action == MotionEvent.ACTION_UP) {
            val endDrawable = editText.compoundDrawablesRelative[2]
            if (endDrawable != null) {
                val isRtl = editText.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val touchTargetWidth = endDrawable.bounds.width() + editText.paddingEnd
                val hitClearIcon = if (isRtl) {
                    event.x <= touchTargetWidth
                } else {
                    event.x >= (editText.width - touchTargetWidth)
                }
                if (hitClearIcon) {
                    editText.text.clear()
                    v.performClick()
                    return@OnTouchListener true
                }
            }
        }
        false
    }

    private val itemListener = object : TopPageAdapter.TopPagesListener {
        override fun onItemClicked(pageInfo: PageInfo) {
            openNewTab(pageInfo.link)
        }
    }

    private val menuListener = object : BrowserHomeListener {
        override fun onBrowserMenuClicked() {
            showPopupMenu()
        }
    }

    private fun handleFirstStartGuide() {
        if (mainActivity.sharedPrefHelper.getIsFirstStart()) {
            mainActivity.settingsViewModel.setIsFirstStart(false)
            navigateToHelp()
        }
    }

    override fun shareWebLink() {}

    override fun bookmarkCurrentUrl() {}
}

package com.example.todtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TodTvProvider : MainAPI() {
    override var name = "TOD TV"
    override var mainUrl = "https://www.todtv.com.tr"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    override var lang = "tr"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val items = mutableListOf<HomePageList>()

        document.select("div.swiper-slide").forEach { element ->
            val title = element.select("h3").text()
            val list = element.select("a").mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) {
                items.add(HomePageList(title, list))
            }
        }

        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("div.title").text().ifEmpty { this.attr("title") }
        val href = this.attr("href") ?: return null
        val posterUrl = this.select("img").attr("src")

        return if (href.contains("/film/")) {
            MovieSearchResponse(
                title,
                fixUrl(href),
                this@TodTvProvider.name,
                TvType.Movie,
                posterUrl
            )
        } else if (href.contains("/dizi/")) {
            TvSeriesSearchResponse(
                title,
                fixUrl(href),
                this@TodTvProvider.name,
                TvType.TvSeries,
                posterUrl
            )
        } else {
            LiveSearchResponse(
                title,
                fixUrl(href),
                this@TodTvProvider.name,
                TvType.Live,
                posterUrl
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=$query"
        val document = app.get(url).document

        return document.select("a.search-result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.select("h1").text()
        val poster = document.select("img.poster").attr("src")
        val description = document.select("div.description").text()

        return if (url.contains("/film/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}

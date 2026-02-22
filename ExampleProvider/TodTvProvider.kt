package com.example.todtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class TodTvProvider : MainAPI() {
    override var name = "TOD TV"
    override var mainUrl = "https://www.todtv.com.tr"
    override var lang = "tr"
    override val hasMainPage = true  // ✅ EKLENDİ
    override val hasSearch = true    // ✅ EKLENDİ
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    // ✅ Kullanıcı oturumu için — TOD TV login gerektirir
    private var authToken: String? = null

    private suspend fun getAuthToken(): String? {
        // TOD TV'nin login API'sine istek at
        // Gerçek endpoint'i TOD TV'nin network trafiğinden bulmanız gerekir
        val response = app.post(
            "$mainUrl/api/auth/login",
            data = mapOf(
                "email" to "KULLANICI_EMAIL",
                "password" to "SIFRE"
            )
        )
        return response.parsedSafe<AuthResponse>()?.token
    }

    data class AuthResponse(val token: String)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val items = mutableListOf<HomePageList>()

        // ✅ Birden fazla bölüm seçici dene
        document.select("div.swiper-slide, div.content-row, section.category").forEach { section ->
            val title = section.select("h2, h3, .section-title").firstOrNull()?.text() ?: return@forEach
            val list = section.select("a[href]").mapNotNull { it.toSearchResult() }
            if (list.isNotEmpty()) {
                items.add(HomePageList(title, list))
            }
        }

        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("div.title, .card-title, span.name").text()
            .ifEmpty { this.attr("title") }
            .ifEmpty { return null }  // ✅ Başlık yoksa atla

        val href = this.attr("href")
        if (href.isBlank()) return null  // ✅ Doğru null kontrolü

        val posterUrl = this.select("img").attr("data-src")
            .ifEmpty { this.select("img").attr("src") }  // ✅ Lazy-load desteği

        return when {
            href.contains("/film/") || href.contains("/movie/") ->
                MovieSearchResponse(title, fixUrl(href), name, TvType.Movie, posterUrl.ifEmpty { null })
            href.contains("/dizi/") || href.contains("/series/") ->
                TvSeriesSearchResponse(title, fixUrl(href), name, TvType.TvSeries, posterUrl.ifEmpty { null })
            href.contains("/canli/") || href.contains("/live/") ->
                LiveSearchResponse(title, fixUrl(href), name, TvType.Live, posterUrl.ifEmpty { null })
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/arama?q=${query.encodeUri()}"  // ✅ URL encode
        val document = app.get(url).document

        return document.select("a.search-result-item, div.search-item a, .result-card a")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.select("h1.title, h1.content-title, h1").firstOrNull()?.text() ?: return null
        val poster = document.select("img.poster, .cover img, meta[property=og:image]")
            .firstOrNull()?.let {
                if (it.tagName() == "meta") it.attr("content") else it.attr("src")
            }
        val description = document.select("div.description, p.synopsis, .content-desc").text()
        val year = document.select(".year, .content-year").text().trim().toIntOrNull()
        val tags = document.select(".genre, .tag").map { it.text() }

        return when {
            url.contains("/film/") || url.contains("/movie/") -> {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                }
            }
            url.contains("/dizi/") || url.contains("/series/") -> {
                // ✅ Bölümleri çek
                val episodes = mutableListOf<Episode>()

                document.select("div.episode-item, .episode-card, a[href*='/bolum/']")
                    .forEachIndexed { index, ep ->
                        val epTitle = ep.select(".episode-title, span").text()
                        val epUrl = ep.attr("href").let { fixUrl(it) }
                        val epNum = ep.select(".episode-number").text().toIntOrNull() ?: (index + 1)
                        val season = ep.attr("data-season").toIntOrNull() ?: 1
                        val epThumb = ep.select("img").attr("src")

                        episodes.add(
                            Episode(
                                epUrl,
                                epTitle,
                                season,
                                epNum,
                                epThumb.ifEmpty { null }
                            )
                        )
                    }

                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {  // ✅ Boş liste değil
                    this.posterUrl = poster
                    this.plot = description
                    this.year = year
                    this.tags = tags
                }
            }
            url.contains("/canli/") || url.contains("/live/") -> {
                LiveStreamLoadResponse(title, url, name, url, poster)
            }
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ✅ Sayfa içindeki video kaynağını bul
        val document = app.get(data).document

        // Yöntem 1: iframe içinde player
        val iframeSrc = document.select("iframe[src]").attr("src")
        if (iframeSrc.isNotBlank()) {
            loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            return true
        }

        // Yöntem 2: Direkt HLS/M3U8 linki script içinde
        val pageText = app.get(data).text
        val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        m3u8Regex.findAll(pageText).forEach { match ->
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    match.groupValues[1],
                    data,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        // Yöntem 3: API üzerinden video URL'si
        val videoId = data.split("/").lastOrNull { it.isNotBlank() }
        if (videoId != null) {
            val apiResponse = app.get(
                "$mainUrl/api/video/$videoId",
                headers = mapOf(
                    "Authorization" to "Bearer ${authToken ?: ""}",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
            val videoUrl = apiResponse.parsedSafe<VideoResponse>()?.url
            if (videoUrl != null) {
                callback.invoke(
                    ExtractorLink(name, name, videoUrl, data, Qualities.Unknown.value, isM3u8 = videoUrl.contains(".m3u8"))
                )
                return true
            }
        }

        return false
    }

    data class VideoResponse(val url: String?, val hls: String?)
}

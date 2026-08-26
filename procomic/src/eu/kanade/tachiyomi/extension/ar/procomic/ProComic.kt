package eu.kanade.tachiyomi.extension.ar.procomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.util.Locale

@Source
abstract class ProComic : HttpSource() {
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(2)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept-Language", "ar,en;q=0.8")

    override fun popularMangaRequest(page: Int): Request = apiRequest(page, search = "_", sort = "popular")

    override fun popularMangaParse(response: Response): MangasPage = parseSearchResponse(response)

    override fun latestUpdatesRequest(page: Int): Request = apiRequest(page, search = "_", sort = "latest_chapter")

    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchResponse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // The public API currently handles one search token more reliably than a phrase.
        val normalizedQuery = query.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return apiRequest(page, search = normalizedQuery)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSearchResponse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val thumbnail = document.select("img[src]")
            .firstOrNull { it.attr("src").contains("/series/") }
            ?.absUrl("src")
            ?.ifBlank { null }
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.trim()
            ?.ifBlank { null }
        val genres = document.select("h3")
            .firstOrNull { it.text().trim() == "التصنيفات" }
            ?.parent()
            ?.select("div.flex.flex-wrap > div")
            ?.map { it.text().trim() }
            ?.filter(String::isNotBlank)
            ?: emptyList()
        val bodyText = document.text()
        val status = when {
            bodyText.contains("مكتمل") -> SManga.COMPLETED
            bodyText.contains("متوقف") -> SManga.ON_HIATUS
            bodyText.contains("مستمر") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            url = document.location().removePrefix(baseUrl).ifBlank { "/ar" }
            this.title = title
            thumbnail_url = thumbnail
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = status
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select("a[href*='/chapter/']")
            .asSequence()
            .mapNotNull { anchor: Element ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val number = CHAPTER_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: return@mapNotNull null
                SChapter.create().apply {
                    url = href.removePrefix(baseUrl)
                    name = anchor.text().trim().ifBlank { "الفصل ${number.toInt()}" }
                    chapter_number = number
                }
            }
            .toList()
        return chapters.distinctBy { chapter -> chapter.url }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val scriptText = document.select("script")
            .asSequence()
            .joinToString("\n") { script: Element -> script.data() + script.html() }
        val imageUrls = APP_IMAGE_REGEX.findAll(scriptText)
            .map { match: MatchResult -> match.value.replace("\\u0026", "&") }
            .distinct()
            .toList()
        val chapterUrl = response.request.url.toString()
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, url = chapterUrl, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headersBuilder()
            .set("Referer", page.url.ifBlank { "$baseUrl/" })
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build(),
    )

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    private fun apiRequest(
        page: Int,
        search: String? = null,
        sort: String? = null,
    ): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/public/series/search")
            .addQueryParameter("status", "approved")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .apply {
                if (!search.isNullOrBlank()) addQueryParameter("search", search)
                if (!sort.isNullOrBlank()) addQueryParameter("sort", sort)
            }
            .build()
        return GET(url, headers)
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.meta.pages > result.meta.page)
    }

    private fun SeriesDto.toSManga() = SManga.create().apply {
        url = "/ar/$slug-$id"
        title = this@toSManga.title
        thumbnail_url = thumbnail ?: coverImage
        description = this@toSManga.description
        status = when (progress?.lowercase(Locale.ROOT)) {
            "مستمر", "ongoing" -> SManga.ONGOING
            "مكتمل", "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private val CHAPTER_NUMBER_REGEX = Regex("-(\\d+)-\\d+(?:$|[/?#])")
        private val APP_IMAGE_REGEX = Regex("https://app\\.procomic\\.(?:net|pro)/chapters/[^\\\"\\s\\\\]+\\.avif")
    }
}

@Serializable
data class SearchResponse(
    val data: List<SeriesDto> = emptyList(),
    val meta: SearchMeta = SearchMeta(),
)

@Serializable
data class SearchMeta(
    val page: Int = 1,
    val pages: Int = 0,
)

@Serializable
data class SeriesDto(
    val id: Int,
    val title: String,
    val slug: String,
    val description: String? = null,
    val progress: String? = null,
    val thumbnail: String? = null,
    val coverImage: String? = null,
)

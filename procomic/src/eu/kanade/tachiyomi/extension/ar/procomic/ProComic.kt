package eu.kanade.tachiyomi.extension.ar.procomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
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

    override fun chapterListRequest(manga: SManga): Request {
        val contentId = CONTENT_ID_REGEX.find(manga.url)?.groupValues?.getOrNull(1).orEmpty()
        val seriesSlug = manga.url.removePrefix("/ar/").substringBeforeLast("-")
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/public/chapters")
            .addQueryParameter("contentId", contentId)
            .addQueryParameter("status", "approved")
            .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
            .addQueryParameter("page", "1")
            .addQueryParameter("seriesSlug", seriesSlug)
            .build()
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val firstPage = response.parseAs<ChapterListResponse>()
        val allChapters = firstPage.chapters.toMutableList()
        var page = 1
        var hasMore = firstPage.hasMore
        val firstPageUrl = response.request.url

        while (hasMore && page < MAX_CHAPTER_PAGES) {
            page++
            val nextUrl = firstPageUrl.newBuilder()
                .setQueryParameter("page", page.toString())
                .build()
            client.newCall(GET(nextUrl, headers)).execute().use { nextResponse ->
                if (!nextResponse.isSuccessful) {
                    hasMore = false
                } else {
                    val nextPage = nextResponse.parseAs<ChapterListResponse>()
                    allChapters += nextPage.chapters
                    hasMore = nextPage.hasMore
                }
            }
        }

        val seriesSlug = firstPageUrl.queryParameter("seriesSlug").orEmpty()
        return allChapters
            .asSequence()
            .filter { it.status.equals("approved", ignoreCase = true) }
            .filter { it.language.equals("AR", ignoreCase = true) }
            .map { chapter ->
                val chapterNumber = chapter.chapterNumber.trim()
                SChapter.create().apply {
                    url = "/ar/chapter/$seriesSlug-$chapterNumber-${chapter.id}"
                    name = chapter.title.trim().ifBlank { "الفصل $chapterNumber" }
                    chapter_number = chapterNumber.toFloatOrNull() ?: 0f
                }
            }
            .distinctBy { chapter -> chapter.url }
            .sortedByDescending { chapter -> chapter.chapter_number }
            .toList()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val scriptText = document.select("script")
            .asSequence()
            .joinToString("\n") { script: Element -> script.data() + script.html() }
        val imageUrls = IMAGE_URL_REGEX.findAll(scriptText)
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
        private const val CHAPTER_PAGE_SIZE = 50
        private const val MAX_CHAPTER_PAGES = 20
        private val CONTENT_ID_REGEX = Regex("-(\\d+)$")
        private val IMAGE_URL_REGEX = Regex(
            "https://app\\.procomic\\.(?:net|pro)/chapters/[^\\\"\\s\\\\]+\\.(?:avif|webp|jpe?g|png)",
        )
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
data class ChapterListResponse(
    val chapters: List<ChapterDto> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
data class ChapterDto(
    val id: Int,
    @SerialName("chapter_number") val chapterNumber: String = "",
    val title: String = "",
    val language: String = "",
    val status: String = "",
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

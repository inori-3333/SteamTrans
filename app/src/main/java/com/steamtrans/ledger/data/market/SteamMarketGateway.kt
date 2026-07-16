package com.steamtrans.ledger.data.market

import android.text.Html
import com.steamtrans.ledger.data.PlatformProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SteamMarketSearchResult(
    val appId: Int,
    val marketHashName: String,
    val displayName: String,
    val imageUrl: String? = null,
    val listingUrl: String,
    val gameName: String = ""
)

data class SteamMarketQuote(
    val grossPriceCents: Long,
    val volume: String = ""
)

data class SteamMarketListing(
    val appId: Int,
    val marketHashName: String,
    val listingUrl: String
)

fun parseSteamMarketListingUrl(value: String): SteamMarketListing {
    val url = value.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请输入有效的 Steam 市场 URL")
    require(url.scheme == "https") { "Steam 市场 URL 必须使用 HTTPS" }
    require(url.host == "steamcommunity.com" || url.host == "www.steamcommunity.com") {
        "仅支持 steamcommunity.com 的市场 URL"
    }
    require(url.port == 443) { "Steam 市场 URL 的端口无效" }
    require(url.username.isEmpty() && url.password.isEmpty()) { "Steam 市场 URL 不能包含登录信息" }

    val path = url.pathSegments.let { segments ->
        if (segments.lastOrNull().isNullOrEmpty()) segments.dropLast(1) else segments
    }
    require(path.size == 4 && path[0] == "market" && path[1] == "listings") {
        "URL 应为 Steam 市场物品页面（/market/listings/AppID/物品名）"
    }
    val appId = path[2].toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Steam 市场 URL 中的 AppID 无效")
    val marketHashName = path[3].trim()
    require(marketHashName.isNotEmpty()) { "Steam 市场 URL 中缺少物品名称" }

    val normalizedUrl = "https://steamcommunity.com".toHttpUrl().newBuilder()
        .addPathSegment("market")
        .addPathSegment("listings")
        .addPathSegment(appId.toString())
        .addPathSegment(marketHashName)
        .build()
        .toString()
    return SteamMarketListing(appId, marketHashName, normalizedUrl)
}

interface SteamMarketGateway {
    suspend fun search(query: String, appId: Int? = null): List<SteamMarketSearchResult>
    suspend fun quote(appId: Int, marketHashName: String): SteamMarketQuote
}

class CommunityMarketGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : SteamMarketGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private val itemNameIdCache = ConcurrentHashMap<String, String>()
    private var lastRequestCompletedAtNanos = 0L

    override suspend fun search(query: String, appId: Int?): List<SteamMarketSearchResult> = withContext(Dispatchers.IO) {
        require(query.trim().length >= 2) { "至少输入 2 个字符" }
        val url = buildSearchUrl(query, appId)
        val root = getJson(url.toString())
        require(root["success"]?.jsonPrimitive?.booleanOrNull != false) { "Steam 市场搜索暂不可用" }
        val results = parseSearchResults(root, appId).ifEmpty {
            parseSearchHtml(root["results_html"]?.jsonPrimitive?.content.orEmpty(), appId)
        }
        val totalCount = root["total_count"]?.jsonPrimitive?.longOrNull ?: 0L
        require(results.isNotEmpty() || totalCount == 0L) { "Steam 返回了搜索结果，但应用暂时无法解析" }
        results
    }

    override suspend fun quote(appId: Int, marketHashName: String): SteamMarketQuote = withContext(Dispatchers.IO) {
        val listingUrl = buildListingUrl(appId, marketHashName)
        val url = "https://steamcommunity.com/market/priceoverview/".toHttpUrl().newBuilder()
            .addQueryParameter("currency", "23")
            .addQueryParameter("appid", appId.toString())
            .addQueryParameter("market_hash_name", marketHashName)
            .build()
        val root = getJson(url.toString(), listingUrl)
        require(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Steam 未返回有效行情" }
        val price = parseLowestListingPrice(root)
            ?: fetchHighestBuyOrderPrice(appId, marketHashName, listingUrl)
            ?: error("Steam 行情既无最低寄售价，也无求购价")
        SteamMarketQuote(price, root["volume"]?.jsonPrimitive?.content.orEmpty())
    }

    private suspend fun fetchHighestBuyOrderPrice(
        appId: Int,
        marketHashName: String,
        listingUrl: String
    ): Long? {
        val cacheKey = "$appId:$marketHashName"
        val itemNameId = itemNameIdCache[cacheKey] ?: parseItemNameId(
            getBody(listingUrl, MarketHomeUrl)
        )?.also { itemNameIdCache[cacheKey] = it } ?: return null
        val histogramUrl = "https://steamcommunity.com/market/itemordershistogram".toHttpUrl().newBuilder()
            .addQueryParameter("country", "CN")
            .addQueryParameter("language", "schinese")
            .addQueryParameter("currency", "23")
            .addQueryParameter("item_nameid", itemNameId)
            .addQueryParameter("two_factor", "0")
            .addQueryParameter("norender", "1")
            .build()
        val root = getJson(histogramUrl.toString(), listingUrl)
        val success = root["success"]?.jsonPrimitive
        require(success?.booleanOrNull == true || success?.intOrNull == 1) { "Steam 未返回有效求购行情" }
        return parseHighestBuyOrderPrice(root)
    }

    private suspend fun getJson(url: String, referer: String? = null) =
        json.parseToJsonElement(getBody(url, referer)).jsonObject

    private suspend fun getBody(url: String, referer: String? = null): String = requestMutex.withLock {
        for (attempt in 0..RateLimitBackoffMillis.size) {
            waitForRequestSlot()
            val result = executeRequest(url, referer)
            lastRequestCompletedAtNanos = System.nanoTime()
            if (!result.rateLimited && !isRateLimitedResponse(result.body)) {
                return@withLock result.body
            }
            if (attempt == RateLimitBackoffMillis.size) {
                error("Steam 市场请求过于频繁，请稍后再试")
            }
            delay(result.retryAfterMillis ?: RateLimitBackoffMillis[attempt])
        }
        error("Steam 市场请求过于频繁，请稍后再试")
    }

    private suspend fun waitForRequestSlot() {
        if (lastRequestCompletedAtNanos == 0L) return
        val elapsedMillis = (System.nanoTime() - lastRequestCompletedAtNanos) / 1_000_000L
        val waitMillis = MinimumRequestIntervalMillis - elapsedMillis
        if (waitMillis > 0) delay(waitMillis)
    }

    private fun executeRequest(url: String, referer: String?): MarketHttpResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .header("User-Agent", "SteamLedger/2.0 (personal offline ledger)")
            .apply { referer?.let { header("Referer", it) } }
            .build()
        return client.newCall(request).execute().use { response ->
            val rateLimited = response.code == 429
            if (!response.isSuccessful && !rateLimited) error("Steam 市场请求失败（${response.code}）")
            val body = response.body?.string().orEmpty()
            if (body.isEmpty() && !rateLimited) error("Steam 市场返回为空")
            val retryAfterMillis = response.header("Retry-After")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?.coerceIn(1_000L, 30_000L)
            MarketHttpResult(body, rateLimited, retryAfterMillis)
        }
    }

    private fun parseSearchHtml(html: String, fallbackAppId: Int?): List<SteamMarketSearchResult> {
        if (html.isBlank()) return emptyList()
        val rowRegex = Regex("""<a[^>]+href=\"([^\"]*/market/listings/(\d+)/([^\"]+))\"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val imageRegex = Regex("""<img[^>]+src=\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""market_listing_item_name[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
        val gameNameRegex = Regex("""market_listing_game_name[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
        return rowRegex.findAll(html).mapNotNull { match ->
            val listingUrl = decodeHtml(match.groupValues[1])
            val appId = match.groupValues[2].toIntOrNull() ?: fallbackAppId ?: return@mapNotNull null
            val hashName = runCatching {
                URLDecoder.decode(match.groupValues[3], StandardCharsets.UTF_8.name())
            }.getOrDefault(match.groupValues[3])
            val body = match.groupValues[4]
            val displayName = nameRegex.find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.let(::decodeHtml)?.trim()
                .orEmpty().ifBlank { hashName }
            val gameName = gameNameRegex.find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.let(::decodeHtml)?.trim().orEmpty()
            SteamMarketSearchResult(
                appId = appId,
                marketHashName = hashName,
                displayName = displayName,
                imageUrl = imageRegex.find(body)?.groupValues?.get(1)?.let(::decodeHtml),
                listingUrl = listingUrl,
                gameName = gameName
            )
        }.distinctBy { it.appId to it.marketHashName }.take(20).toList()
    }

    private fun decodeHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    companion object {
        private const val ImageBaseUrl = "https://community.fastly.steamstatic.com/economy/image"
        private const val MarketHomeUrl = "https://steamcommunity.com/market/"
        private const val MinimumRequestIntervalMillis = 1_000L
        private val RateLimitBackoffMillis = longArrayOf(2_000L, 5_000L, 10_000L)

        fun buildListingUrl(appId: Int, marketHashName: String): String =
            "https://steamcommunity.com".toHttpUrl().newBuilder()
                .addPathSegment("market")
                .addPathSegment("listings")
                .addPathSegment(appId.toString())
                .addPathSegment(marketHashName)
                .build()
                .toString()

        fun buildSearchUrl(query: String, appId: Int?): HttpUrl {
            val builder = "https://steamcommunity.com/market/search/render/".toHttpUrl().newBuilder()
                .addQueryParameter("query", query.trim())
                .addQueryParameter("start", "0")
                .addQueryParameter("count", "20")
                .addQueryParameter("search_descriptions", "0")
                .addQueryParameter("sort_column", "popular")
                .addQueryParameter("sort_dir", "desc")
                .addQueryParameter("norender", "1")
            appId?.let { builder.addQueryParameter("appid", it.toString()) }
            return builder.build()
        }

        fun parseSearchResults(root: JsonObject, fallbackAppId: Int?): List<SteamMarketSearchResult> {
            val results = root["results"] as? JsonArray ?: return emptyList()
            return results.mapNotNull { element ->
                val result = element as? JsonObject ?: return@mapNotNull null
                val asset = result["asset_description"] as? JsonObject
                val appId = asset?.intValue("appid") ?: fallbackAppId ?: return@mapNotNull null
                val hashName = asset?.stringValue("market_hash_name")
                    ?: result.stringValue("hash_name")
                    ?: return@mapNotNull null
                val displayName = result.stringValue("name")
                    ?: asset?.stringValue("market_name")
                    ?: hashName
                val iconUrl = asset?.stringValue("icon_url")?.takeIf { it.isNotBlank() }
                SteamMarketSearchResult(
                    appId = appId,
                    marketHashName = hashName,
                    displayName = displayName,
                    imageUrl = iconUrl?.let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it
                        else "$ImageBaseUrl/$it/96fx96f"
                    },
                    listingUrl = "https://steamcommunity.com".toHttpUrl().newBuilder()
                        .addPathSegment("market")
                        .addPathSegment("listings")
                        .addPathSegment(appId.toString())
                        .addPathSegment(hashName)
                        .build()
                        .toString(),
                    gameName = result.stringValue("app_name").orEmpty()
                )
            }.distinctBy { it.appId to it.marketHashName }.take(20)
        }

        private fun JsonObject.stringValue(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

        private fun JsonObject.intValue(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull

        fun parseCnyPrice(value: String): Long? = runCatching {
            val cleaned = value.replace("¥", "").replace("￥", "").replace("CNY", "", true)
                .replace(" ", "").replace(",", "").trim()
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()

        fun parseLowestListingPrice(root: JsonObject): Long? =
            root["lowest_price"]?.jsonPrimitive?.contentOrNull?.let(::parseCnyPrice)?.takeIf { it > 0 }

        fun parseItemNameId(html: String): String? {
            val patterns = listOf(
                Regex("""Market_LoadOrderSpread\(\s*(\d+)\s*\)"""),
                Regex("""ItemActivityTicker\.Start\(\s*(\d+)\s*\)""")
            )
            return patterns.firstNotNullOfOrNull { pattern ->
                pattern.find(html)?.groupValues?.get(1)
            }
        }

        fun isRateLimitedResponse(body: String): Boolean {
            val normalized = body.lowercase()
            return normalized.contains("too many requests") ||
                normalized.contains("请求次数过多") ||
                normalized.contains("请求过于频繁") ||
                normalized.contains("访问过于频繁")
        }

        fun parseHighestBuyOrderPrice(root: JsonObject): Long? {
            val rawCents = root["highest_buy_order"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            if (rawCents != null) return rawCents
            return root["buy_order_price"]?.jsonPrimitive?.contentOrNull
                ?.let(::parseCnyPrice)
                ?.takeIf { it > 0 }
        }
    }
}

private data class MarketHttpResult(
    val body: String,
    val rateLimited: Boolean,
    val retryAfterMillis: Long?
)

fun estimateNetPrice(grossCents: Long, steamProfile: PlatformProfileEntity?): Long {
    if (grossCents <= 0) return 0
    val rate = steamProfile?.sellFeeRateBps ?: 1_500
    val fixed = steamProfile?.sellFixedFeeCents ?: 0
    val percentage = BigDecimal.valueOf(grossCents)
        .multiply(BigDecimal.valueOf(rate.toLong()))
        .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.CEILING)
        .longValueExact()
    return (grossCents - percentage - fixed).coerceAtLeast(0)
}

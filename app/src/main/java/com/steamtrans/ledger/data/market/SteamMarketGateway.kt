package com.steamtrans.ledger.data.market

import android.text.Html
import com.steamtrans.ledger.data.PlatformProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        delay(180)
        val url = "https://steamcommunity.com/market/priceoverview/".toHttpUrl().newBuilder()
            .addQueryParameter("currency", "23")
            .addQueryParameter("appid", appId.toString())
            .addQueryParameter("market_hash_name", marketHashName)
            .build()
        val root = getJson(url.toString())
        require(root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Steam 未返回有效行情" }
        val price = parseLowestListingPrice(root)
            ?: fetchHighestBuyOrderPrice(appId, marketHashName)
            ?: error("Steam 行情既无最低寄售价，也无求购价")
        SteamMarketQuote(price, root["volume"]?.jsonPrimitive?.content.orEmpty())
    }

    private fun fetchHighestBuyOrderPrice(appId: Int, marketHashName: String): Long? {
        val listingUrl = "https://steamcommunity.com".toHttpUrl().newBuilder()
            .addPathSegment("market")
            .addPathSegment("listings")
            .addPathSegment(appId.toString())
            .addPathSegment(marketHashName)
            .build()
        val itemNameId = parseItemNameId(getBody(listingUrl.toString()))
            ?: error("Steam 市场物品页缺少订单标识")
        val histogramUrl = "https://steamcommunity.com/market/itemordershistogram".toHttpUrl().newBuilder()
            .addQueryParameter("country", "CN")
            .addQueryParameter("language", "schinese")
            .addQueryParameter("currency", "23")
            .addQueryParameter("item_nameid", itemNameId)
            .addQueryParameter("two_factor", "0")
            .build()
        val root = getJson(histogramUrl.toString())
        val success = root["success"]?.jsonPrimitive
        require(success?.booleanOrNull == true || success?.intOrNull == 1) { "Steam 未返回有效求购行情" }
        return parseHighestBuyOrderPrice(root)
    }

    private fun getJson(url: String) = json.parseToJsonElement(getBody(url)).jsonObject

    private fun getBody(url: String) = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .header("User-Agent", "SteamLedger/2.0 (personal offline ledger)")
            .build()
    ).execute().use { response ->
        if (!response.isSuccessful) error("Steam 市场请求失败（${response.code}）")
        response.body?.string() ?: error("Steam 市场返回为空")
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

        fun parseItemNameId(html: String): String? =
            Regex("""Market_LoadOrderSpread\(\s*(\d+)\s*\)""")
                .find(html)
                ?.groupValues
                ?.get(1)

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

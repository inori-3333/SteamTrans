package com.steamtrans.ledger.data.market

import android.text.Html
import com.steamtrans.ledger.data.PlatformProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        parseSearchHtml(root["results_html"]?.jsonPrimitive?.content.orEmpty(), appId)
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
        val priceText = root["lowest_price"]?.jsonPrimitive?.content.orEmpty()
        val price = parseCnyPrice(priceText) ?: error("Steam 行情缺少最低挂牌价")
        SteamMarketQuote(price, root["volume"]?.jsonPrimitive?.content.orEmpty())
    }

    private fun getJson(url: String) = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            .header("User-Agent", "SteamLedger/2.0 (personal offline ledger)")
            .build()
    ).execute().use { response ->
        if (!response.isSuccessful) error("Steam 市场请求失败（${response.code}）")
        val body = response.body?.string() ?: error("Steam 市场返回为空")
        json.parseToJsonElement(body).jsonObject
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

        fun parseCnyPrice(value: String): Long? = runCatching {
            val cleaned = value.replace("¥", "").replace("￥", "").replace("CNY", "", true)
                .replace(" ", "").replace(",", "").trim()
            BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
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

package com.steamtrans.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SentimentSatisfiedAlt
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.steamtrans.ledger.data.ItemEntity
import com.steamtrans.ledger.data.ItemType
import com.steamtrans.ledger.formatMoney
import com.steamtrans.ledger.ui.theme.RaisedBlue
import com.steamtrans.ledger.ui.theme.TextSecondary
import com.steamtrans.ledger.ui.theme.categoryColor

@Composable
fun ScreenHeading(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        action?.invoke()
    }
}

@Composable
fun SectionHeading(title: String, meta: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}

@Composable
fun Amount(
    cents: Long,
    color: Color = MaterialTheme.colorScheme.onSurface,
    signed: Boolean = false,
    large: Boolean = false
) {
    Text(
        formatMoney(cents, signed),
        color = color,
        style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp)) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = color)
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun ItemArtwork(item: ItemEntity, modifier: Modifier = Modifier) {
    val color = categoryColor(item.type)
    Box(
        modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            defaultArtworkIcon(item.type),
            item.type.label,
            tint = color,
            modifier = Modifier.size(25.dp)
        )
        if (!item.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().padding(4.dp)
            )
        }
    }
}

internal fun defaultArtworkIcon(type: ItemType): ImageVector = when (type) {
    ItemType.SKIN -> Icons.Outlined.SportsEsports
    ItemType.CASE_CONTAINER -> Icons.Outlined.Inventory2
    ItemType.CAPSULE -> Icons.Outlined.Science
    ItemType.SOUVENIR_PACKAGE -> Icons.Outlined.Redeem
    ItemType.GEM_SACK -> Icons.Outlined.LocalMall
    ItemType.GEM -> Icons.Outlined.Diamond
    ItemType.CARD -> Icons.Outlined.Style
    ItemType.FOIL_CARD -> Icons.Outlined.AutoAwesome
    ItemType.BOOSTER -> Icons.Outlined.ViewCarousel
    ItemType.BACKGROUND -> Icons.Outlined.Wallpaper
    ItemType.EMOTICON -> Icons.Outlined.SentimentSatisfiedAlt
    ItemType.OTHER -> Icons.Outlined.Category
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(58.dp).background(RaisedBlue, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(7.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

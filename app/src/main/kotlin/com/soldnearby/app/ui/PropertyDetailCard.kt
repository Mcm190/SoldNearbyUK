package com.soldnearby.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soldnearby.app.R
import com.soldnearby.app.data.SoldProperty
import com.soldnearby.app.ui.theme.BrandGreenLight
import com.soldnearby.app.ui.theme.MultiSaleRed
import com.soldnearby.app.util.formatGbp
import com.soldnearby.app.util.propertyTypeLabel
import com.soldnearby.app.util.tenureLabel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun PropertyDetailCard(
    property: SoldProperty,
    nearbySales: List<SoldProperty>,
    saleHistory: List<SoldProperty>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Average of everything else nearby, not including this property itself, so a single
    // very expensive or cheap sale doesn't just compare itself to itself.
    val peers = nearbySales.filter { it.id != property.id }
    val averagePrice = if (peers.isNotEmpty()) peers.map { it.priceGbp }.average() else null

    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        // Capped and scrollable rather than left to grow unbounded — a property with a long
        // sale history plus the comparison text could otherwise push past the screen on a
        // small phone.
        Column(
            modifier = Modifier
                .padding(20.dp)
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = formatGbp(property.priceGbp), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Text(
                text = property.address,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${property.propertyTypeLabel()} · ${property.tenureLabel()} · sold ${property.dateOfTransfer}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = comparisonText(property, averagePrice, peers.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.sale_history), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            if (saleHistory.size <= 1) {
                Text(
                    text = stringResource(R.string.no_previous_sales),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                PriceHistorySparkline(
                    history = saleHistory,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                saleHistory.forEachIndexed { index, sale ->
                    // saleHistory is newest-first, so the entry one *after* this one in the
                    // list is the chronologically previous sale to compare against.
                    HistoryRow(
                        sale = sale,
                        previousSale = saleHistory.getOrNull(index + 1),
                        isTappedTransaction = sale.id == property.id
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(sale: SoldProperty, previousSale: SoldProperty?, isTappedTransaction: Boolean) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column {
            Text(
                text = formatGbp(sale.priceGbp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isTappedTransaction) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = sale.dateOfTransfer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (previousSale != null) {
            val roseInValue = sale.priceGbp >= previousSale.priceGbp
            Text(
                text = priceChangeLabel(sale.priceGbp, previousSale.priceGbp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (roseInValue) BrandGreenLight else MultiSaleRed
            )
        }
    }
}

private fun priceChangeLabel(current: Long, previous: Long): String {
    if (previous == 0L) return ""
    val diffPercent = ((current - previous).toDouble() / previous) * 100
    val arrow = if (diffPercent >= 0) "▲" else "▼"
    return "$arrow ${abs(diffPercent).roundToInt()}%"
}

/** A minimal hand-drawn line chart of price over time — no charting dependency needed for one
 *  sparkline, Compose's own [Canvas] is enough. [history] is newest-first (as returned by
 *  PriceDatabase.historyForAddress); re-sorted to oldest-first here so the line reads left to
 *  right like a normal trend chart. */
@Composable
private fun PriceHistorySparkline(history: List<SoldProperty>, modifier: Modifier = Modifier) {
    val chronological = history.sortedBy { it.dateOfTransfer }
    val prices = chronological.map { it.priceGbp.toFloat() }
    val minPrice = prices.min()
    val maxPrice = prices.max()
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.fillMaxWidth().height(56.dp)) {
        val range = (maxPrice - minPrice).coerceAtLeast(1f)
        val stepX = if (prices.size > 1) size.width / (prices.size - 1) else 0f
        val points = prices.mapIndexed { index, price ->
            Offset(
                x = stepX * index,
                y = size.height - ((price - minPrice) / range) * size.height
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = lineColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }
        points.forEach { point -> drawCircle(color = lineColor, radius = 6f, center = point) }
    }
}

private fun comparisonText(property: SoldProperty, averagePrice: Double?, peerCount: Int): String {
    if (averagePrice == null) {
        return "Only sale in this dataset near here — nothing nearby to compare it to yet."
    }
    val diffPercent = ((property.priceGbp - averagePrice) / averagePrice) * 100
    val direction = if (diffPercent >= 0) "above" else "below"
    val peerWord = if (peerCount == 1) "sale" else "sales"
    return "${abs(diffPercent).roundToInt()}% $direction the ${formatGbp(averagePrice.roundToLong())} average " +
        "of $peerCount nearby $peerWord"
}

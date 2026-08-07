package com.soldnearby.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.soldnearby.app.R
import com.soldnearby.app.data.SoldProperty
import com.soldnearby.app.util.formatGbp

/** Shown when a red (multi-address) dot is tapped — one dot on the map can stand in for a
 *  whole block of flats sharing a single ONSPD postcode coordinate, so tapping it can't go
 *  straight to a single property's detail card the way a single-address dot does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressPickerSheet(
    properties: List<SoldProperty>,
    onSelect: (SoldProperty) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val sortedProperties = properties.sortedBy { it.address }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.select_an_address),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = pluralAddressesLabel(sortedProperties.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
        }
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            items(sortedProperties, key = { it.id }) { property ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(property) }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(text = property.address, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${formatGbp(property.priceGbp)} · sold ${property.dateOfTransfer}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

private fun pluralAddressesLabel(count: Int): String =
    if (count == 1) "1 address at this location" else "$count addresses at this location"

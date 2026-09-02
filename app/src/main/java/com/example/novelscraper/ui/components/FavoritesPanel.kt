package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelscraper.ui.theme.AppColors

@Composable
fun FavoritesPanel(
    favorites: Map<String, String>,
    onFavoriteClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedFavorites = remember(favorites) {
        favorites.keys.sorted()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.backgroundDarkest)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "★ ブックマーク",
                color = AppColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onCloseClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceLight),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("閉じる", color = AppColors.textPrimary, fontSize = 12.sp)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            items(
                items = sortedFavorites,
                key = { it },
                contentType = { "favorite_item" }
            ) { name ->
                val url = favorites[name] ?: ""
                FavoriteItem(
                    name = name,
                    url = url,
                    onClick = { onFavoriteClick(url) },
                    onDelete = { onDeleteClick(name) }
                )
            }
        }
    }
}

@Composable
private fun FavoriteItem(
    name: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(AppColors.backgroundMedium, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = AppColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = url,
                color = AppColors.textTertiary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.error),
            shape = RoundedCornerShape(4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp)
        ) {
            Text("削除", fontSize = 11.sp, color = Color.White)
        }
    }
}

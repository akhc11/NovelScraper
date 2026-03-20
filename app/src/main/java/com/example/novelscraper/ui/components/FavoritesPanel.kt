package com.example.novelscraper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FavoritesPanel(
    favorites: Map<String, String>,
    onFavoriteClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF2D2D2D))
    ) {
        Text(
            text = "★ ブックマーク",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(15.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            val list = favorites.keys.toList().sorted()
            items(list) { name ->
                val url = favorites[name] ?: ""
                FavoriteItem(
                    name = name,
                    url = url,
                    onClick = { onFavoriteClick(url) },
                    onDelete = { onDeleteClick(name) }
                )
            }
        }

        Button(
            onClick = onCloseClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Text("閉じる", color = Color.White)
        }
    }
}

@Composable
fun FavoriteItem(
    name: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = url, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
        }
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("削除", fontSize = 12.sp, color = Color.White)
        }
    }
}

package io.github.helterfall.ramen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RamenBackground = Color(0xFF101216)
private val RamenSurface = Color(0xFF1A1E25)
private val RamenSurfaceRaised = Color(0xFF222832)
private val RamenAccent = Color(0xFFF2B766)
private val RamenText = Color(0xFFF4F1EA)
private val RamenMuted = Color(0xFFA8ADB7)

@Composable
fun RamenApp() {
    val colors = darkColorScheme(
        primary = RamenAccent,
        onPrimary = Color(0xFF2B1D0A),
        secondary = Color(0xFFB8CBBE),
        background = RamenBackground,
        surface = RamenSurface,
        surfaceVariant = RamenSurfaceRaised,
        onSurface = RamenText,
        onSurfaceVariant = RamenMuted
    )

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(
                    horizontal = 22.dp,
                    vertical = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                item { BrandHeader() }
                item { EmptyLibraryCard() }
                item { ComingNextSection() }
                item { BuildFooter() }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(RamenAccent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "R",
                color = Color(0xFF24190B),
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(modifier = Modifier.size(13.dp))

        Column {
            Text(
                text = "RAMEN",
                color = RamenText,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "VISUAL NOVEL PLAYER",
                color = RamenMuted,
                fontSize = 10.sp,
                letterSpacing = 1.3.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            color = RamenSurfaceRaised,
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = "0.1.0",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = RamenMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = RamenSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(23.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "ТВОЯ БИБЛИОТЕКА",
                color = RamenAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "Истории\nначинаются здесь.",
                color = RamenText,
                fontSize = 30.sp,
                lineHeight = 35.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Ramen собирает визуальные новеллы в одном месте. Сейчас мы готовим основу, чтобы запускать их через подходящие игровые движки.",
                color = RamenMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(RamenSurfaceRaised)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "0",
                        color = RamenText,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "игр добавлено",
                        color = RamenMuted,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "ИМПОРТ\nСКОРО",
                    color = RamenAccent,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun ComingNextSection() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Что появится дальше",
            color = RamenText,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )

        FeatureRow(
            number = "01",
            title = "Импорт новелл",
            description = "Добавление папок и архивов с играми"
        )
        FeatureRow(
            number = "02",
            title = "Распознавание движков",
            description = "Определение формата и совместимости"
        )
        FeatureRow(
            number = "03",
            title = "Сохранения и прогресс",
            description = "Возвращайся к истории с нужного места"
        )
    }
}

@Composable
private fun FeatureRow(
    number: String,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RamenSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(RamenSurfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = RamenAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.size(13.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RamenText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = RamenMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Text(
                text = "СКОРО",
                color = RamenMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun BuildFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RamenSurfaceRaised)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "СДЕЛАНО ДЛЯ ИСТОРИЙ",
            color = RamenMuted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RamenAppPreview() {
    RamenApp()
}

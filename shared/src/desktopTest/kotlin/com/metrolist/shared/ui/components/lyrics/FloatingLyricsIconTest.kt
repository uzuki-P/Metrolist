package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.Image
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.floating_lyrics_lock_icon
import org.jetbrains.compose.resources.painterResource
import kotlin.test.Test

class FloatingLyricsIconTest {
    @Test
    fun lockIconLoadsOnDesktop() {
        ImageComposeScene(width = 64, height = 64).use { scene ->
            scene.setContent { Image(painterResource(Res.drawable.floating_lyrics_lock_icon), contentDescription = null) }
            repeat(3) { scene.render(it * 16_000_000L) }
        }
    }
}

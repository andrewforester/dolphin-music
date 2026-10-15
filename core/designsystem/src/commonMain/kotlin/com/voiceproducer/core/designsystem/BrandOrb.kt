package com.voiceproducer.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.voiceproducer.core.designsystem.generated.resources.Res
import com.voiceproducer.core.designsystem.generated.resources.brand_orb
import org.jetbrains.compose.resources.painterResource

@Composable
fun BrandOrb(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.brand_orb), contentDescription = null, modifier = modifier)
}

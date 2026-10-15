package com.voiceproducer.feature.producer.ui.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.voiceproducer.core.designsystem.Palette
import com.voiceproducer.feature.producer.orb.OrbMood
import com.voiceproducer.feature.producer.orb.orbPulseScale
import kotlin.math.min

typealias OrbRenderer = @Composable (look: OrbLook, modifier: Modifier) -> Unit

private class MoodStyle(
    val colors: List<Color>,
    val speed: Float,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
)

private const val OFF_SPEED = 0.15f
private const val OFF_SATURATION = 0.95f
private const val OFF_BRIGHTNESS = 1f
private const val CONNECTING_SPEED = 2f
private const val LISTENING_SPEED = 0.6f
private const val ACTIVE_SPEED = 1.2f
private const val THINKING_SPEED = 0.8f
private const val MUTED_SPEED = 0.3f
private const val MUTED_SATURATION = 0.15f

private fun style(mood: OrbMood): MoodStyle =
    when (mood) {
        OrbMood.Off -> {
            MoodStyle(
                listOf(Palette.ShaderTeal, Palette.Moss, Palette.Pine, Palette.Cream),
                OFF_SPEED,
                saturation = OFF_SATURATION,
                brightness = OFF_BRIGHTNESS,
            )
        }

        OrbMood.Connecting -> {
            MoodStyle(listOf(Palette.Teal, Palette.Amber, Palette.Moss, Palette.Ochre), CONNECTING_SPEED)
        }

        OrbMood.Listening -> {
            MoodStyle(listOf(Palette.Teal, Palette.Cream, Palette.Moss, Palette.Teal), LISTENING_SPEED)
        }

        OrbMood.HearingYou -> {
            MoodStyle(listOf(Palette.Amber, Palette.Ochre, Palette.Teal, Palette.Cream), ACTIVE_SPEED)
        }

        OrbMood.Speaking -> {
            MoodStyle(listOf(Palette.Teal, Palette.Moss, Palette.Cream, Palette.Teal), ACTIVE_SPEED)
        }

        OrbMood.Thinking -> {
            MoodStyle(listOf(Palette.Amber, Palette.Teal, Palette.Ochre, Palette.Moss), THINKING_SPEED)
        }

        OrbMood.Muted -> {
            MoodStyle(listOf(Palette.Teal, Palette.Cream, Palette.Moss, Palette.Teal), MUTED_SPEED, saturation = MUTED_SATURATION)
        }
    }

private const val MOOD_FADE_MS = 300
private const val VOICE_FADE_MS = 120
private const val SCALE_FOLLOW = 12f
private const val ORB_COLORS = 4
private const val NANOS_PER_SECOND = 1e9f
private const val MILLIS_PER_SECOND = 1000
private const val TIME_WRAP_SECONDS = 1000f

@Stable
class OrbLook internal constructor(
    private val colorStates: List<State<Color>>,
    private val speedState: State<Float>,
    private val saturationState: State<Float>,
    private val brightnessState: State<Float>,
    private val scaleState: State<Float>,
    private val timeState: State<Float>,
) {
    fun color(index: Int): Color = colorStates[index].value

    val flowSpeed: Float get() = speedState.value
    val saturation: Float get() = saturationState.value
    val brightness: Float get() = brightnessState.value
    val scale: Float get() = scaleState.value
    val time: Float get() = timeState.value
}

@Composable
fun rememberOrbLook(
    mood: OrbMood,
    voiceLevel: Float,
): OrbLook {
    val target = style(mood)
    val colors = List(ORB_COLORS) { animateColorAsState(target.colors[it], tween(MOOD_FADE_MS), label = "orb colour $it") }
    val speed = animateFloatAsState(target.speed, tween(MOOD_FADE_MS), label = "orb speed")
    val saturation = animateFloatAsState(target.saturation, tween(MOOD_FADE_MS), label = "orb saturation")
    val brightness = animateFloatAsState(target.brightness, tween(MOOD_FADE_MS), label = "orb brightness")
    val voice = animateFloatAsState(voiceLevel, tween(VOICE_FADE_MS), label = "orb voice")
    val scale = remember { mutableFloatStateOf(1f) }
    val time = remember { mutableFloatStateOf(0f) }
    val currentMood by rememberUpdatedState(mood)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = withInfiniteAnimationFrameNanos { it }
            var elapsedMs = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    val dt = (now - last) / NANOS_PER_SECOND
                    last = now
                    elapsedMs += (dt * MILLIS_PER_SECOND).toLong()
                    time.floatValue = (time.floatValue + dt * speed.value) % TIME_WRAP_SECONDS
                    val goal = orbPulseScale(currentMood, voice.value, elapsedMs)
                    scale.floatValue += (goal - scale.floatValue) * min(1f, dt * SCALE_FOLLOW)
                }
            }
        }
    }
    return remember { OrbLook(colors, speed, saturation, brightness, scale, time) }
}

val StaticOrb: OrbRenderer = { look, modifier ->
    Spacer(
        modifier
            .graphicsLayer {
                scaleX = look.scale
                scaleY = look.scale
            }.drawBehind { drawStaticSphere(look) },
    )
}

val ShaderOrb: OrbRenderer = { look, modifier -> ShaderOrbContent(look, modifier) }

@Composable
private fun ShaderOrbContent(
    look: OrbLook,
    modifier: Modifier = Modifier,
) {
    val shader = rememberOrbShader(ORB_SHADER)
    if (shader == null) {
        StaticOrb(look, modifier)
        return
    }
    Spacer(
        modifier
            .graphicsLayer {
                scaleX = look.scale
                scaleY = look.scale
            }.drawBehind {
                drawGlow(tone(look.color(0), look.saturation, look.brightness))
                shader.uniform("uRes", size.width, size.height)
                shader.uniform("uTime", look.time)
                for (i in 0 until ORB_COLORS) {
                    val c = look.color(i)
                    shader.uniform("uC$i", c.red, c.green, c.blue)
                }
                shader.uniform("uSat", look.saturation)
                shader.uniform("uBright", look.brightness)
                drawRect(shader.brush())
            },
    )
}

private const val HIGHLIGHT_MIX = 0.35f
private const val SHADE_MIX = 0.25f
private const val HIGHLIGHT_X = -0.35f
private const val HIGHLIGHT_Y = -0.4f
private const val SPHERE_GRADIENT_RADIUS = 1.45f
private const val MID_STOP = 0.45f
private const val RIM_STOP = 0.8f
private const val GLOW_ALPHA = 0.35f
private const val GLOW_START = 0.6f
private const val GLOW_RADIUS = 1.35f
private const val LUMA_R = 0.299f
private const val LUMA_G = 0.587f
private const val LUMA_B = 0.114f

private fun DrawScope.drawStaticSphere(look: OrbLook) {
    val tones = List(ORB_COLORS) { tone(look.color(it), look.saturation, look.brightness) }
    val low = tones.first()
    val deep = tones.last()
    val (_, mid, high) = tones
    drawGlow(low)
    val r = size.minDimension / 2
    drawCircle(
        Brush.radialGradient(
            0f to lerp(high, Color.White, HIGHLIGHT_MIX),
            MID_STOP to mid,
            RIM_STOP to low,
            1f to lerp(deep, Color.Black, SHADE_MIX),
            center = center + Offset(HIGHLIGHT_X * r, HIGHLIGHT_Y * r),
            radius = SPHERE_GRADIENT_RADIUS * r,
        ),
        radius = r,
    )
}

private fun DrawScope.drawGlow(color: Color) {
    val r = size.minDimension / 2
    drawCircle(
        Brush.radialGradient(
            GLOW_START to color.copy(alpha = GLOW_ALPHA),
            1f to Color.Transparent,
            center = center,
            radius = GLOW_RADIUS * r,
        ),
        radius = GLOW_RADIUS * r,
    )
}

private fun tone(
    color: Color,
    saturation: Float,
    brightness: Float,
): Color {
    val grey = LUMA_R * color.red + LUMA_G * color.green + LUMA_B * color.blue

    fun channel(v: Float) = ((grey + (v - grey) * saturation) * brightness).coerceIn(0f, 1f)
    return Color(channel(color.red), channel(color.green), channel(color.blue), color.alpha)
}

internal const val ORB_SHADER = """
uniform float2 uRes;
uniform float uTime;
uniform float3 uC0;
uniform float3 uC1;
uniform float3 uC2;
uniform float3 uC3;
uniform float uSat;
uniform float uBright;

float h(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

float n(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), f.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * n(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 p = fragCoord / uRes * 2.0 - 1.0;
    float r = length(p);
    if (r > 1.0) return half4(0.0);
    float t = uTime;
    float2 q = float2(fbm(p * 1.4 + float2(t * 0.23, -t * 0.17)), fbm(p * 1.4 + float2(-t * 0.19, t * 0.21) + 5.2));
    float a = atan(p.y, p.x) + t * 0.35 + q.x * 2.4;
    float m = mix(0.5 + 0.5 * sin(q.x * 7.0 + q.y * 3.0), 0.5 + 0.5 * sin(a * 2.0 + q.y * 4.0), smoothstep(0.05, 0.5, r));
    float k = smoothstep(0.15, 0.95, 0.5 + 0.5 * p.y + (q.x - 0.5) * 0.8);
    float3 col = mix(mix(uC0, uC1, m), mix(uC2, uC3, q.y), k);
    float z = sqrt(max(0.0, 1.0 - r * r));
    col *= 0.72 + 0.28 * z;
    col += 0.4 * (1.0 - smoothstep(0.0, 0.6, length(p - float2(-0.35, -0.42))));
    col += 0.15 * pow(1.0 - z, 3.0);
    float g = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(g), col, uSat) * uBright;
    float alpha = 1.0 - smoothstep(0.97, 1.0, r);
    return half4(half3(clamp(col, 0.0, 1.0) * alpha), alpha);
}
"""

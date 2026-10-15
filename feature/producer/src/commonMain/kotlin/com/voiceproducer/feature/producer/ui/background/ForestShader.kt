package com.voiceproducer.feature.producer.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.voiceproducer.feature.producer.AgentActivity
import com.voiceproducer.feature.producer.ProducerUiState
import com.voiceproducer.feature.producer.SectionKind
import com.voiceproducer.feature.producer.TrackStatus
import com.voiceproducer.feature.producer.ui.currentSectionIndex
import kotlin.math.pow

@Composable
expect fun ShaderBackground(
    state: ProducerUiState,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
)

interface UniformSink {
    fun float(
        name: String,
        value: Float,
    )

    fun float2(
        name: String,
        x: Float,
        y: Float,
    )
}

private const val ENERGY_RATE = 0.04f
private const val LISTEN_RATE = 0.08f
private const val GEN_RATE = 0.05f
private const val HEAT_RATE = 0.04f
private const val BASE_SPEED = 0.3f
private const val ENERGY_SPEED = 1.2f
private const val CHORUS_HEAT = 0.35f

internal class ShaderDriver {
    var time = 0f
        private set
    var energy = 0.25f
        private set
    var heat = 0f
        private set
    var listen = 0f
        private set
    var gen = 0f
        private set

    fun step(
        dtSeconds: Float,
        state: ProducerUiState,
        positionMs: Long,
    ) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        val frames = dt * 60f

        fun approach(
            value: Float,
            target: Float,
            perFrame: Float,
        ) = value + (target - value) * (1f - (1f - perFrame).pow(frames))

        val generating = state.track == TrackStatus.Generating
        val listening = state.agentActivity == AgentActivity.Listening
        val playing = state.isPlaying
        val volume = state.musicLevel
        val energyTarget =
            when {
                generating -> 0.7f
                playing -> 0.35f + 0.65f * volume
                listening -> 0.45f
                else -> 0.25f
            }
        energy = approach(energy, energyTarget, ENERGY_RATE)
        listen = approach(listen, if (listening) 1f else 0f, LISTEN_RATE)
        gen = approach(gen, if (generating) 1f else 0f, GEN_RATE)
        heat = approach(heat, heatTarget(state, positionMs), HEAT_RATE)
        time += dt * (BASE_SPEED + energy * ENERGY_SPEED)
    }

    fun writeUniforms(
        width: Float,
        height: Float,
        sink: UniformSink,
    ) {
        sink.float2("uRes", width, height)
        sink.float("uTime", time)
        sink.float("uEnergy", energy)
        sink.float("uHeat", heat)
        sink.float("uListen", listen)
        sink.float("uGen", gen)
    }

    private fun heatTarget(
        state: ProducerUiState,
        positionMs: Long,
    ): Float {
        val section =
            if (state.isPlaying) {
                val index = currentSectionIndex(state.sections, positionMs)
                state.sections.firstOrNull { it.index == index }
            } else {
                null
            }
        return when {
            section == null -> 0f
            section.version > 1 -> 1f
            section.kind == SectionKind.Chorus -> CHORUS_HEAT
            else -> 0f
        }
    }
}

const val FOREST_SHADER = """
uniform float2 uRes;
uniform float uTime;
uniform float uEnergy;
uniform float uHeat;
uniform float uListen;
uniform float uGen;

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
    for (int i = 0; i < 5; i++) {
        v += a * n(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = float2(fragCoord.x, uRes.y - fragCoord.y) / uRes;
    float2 p = (uv - 0.5) * float2(uRes.x / uRes.y, 1.0);
    float t = uTime;
    float2 q = float2(fbm(p * 1.3 + float2(t * 0.05, 0.0)), fbm(p * 1.3 - float2(0.0, t * 0.045) + 3.1));
    float r = fbm(p * 1.8 + q * (1.5 + uEnergy * 1.3) + float2(t * 0.09, -t * 0.04));
    float3 pine = float3(0.06, 0.4, 0.5);
    float3 teal = float3(0.1, 0.62, 0.7);
    float3 moss = float3(0.32, 0.84, 0.84);
    float3 fern = float3(0.72, 0.97, 0.95);
    float3 sun = float3(1.0, 0.86, 0.66);
    float3 birch = float3(0.9, 0.98, 1.0);
    float3 col = mix(pine, teal, smoothstep(0.2, 0.6, q.x));
    col = mix(col, moss, smoothstep(0.35, 0.72, r));
    col = mix(col, fern, smoothstep(0.55, 0.9, r + q.y * 0.2) * 0.8);
    float3 irid = 0.5 + 0.5 * cos(6.2831 * (r * 1.6 + q.x * 0.6 + float3(0.0, 0.18, 0.42) + t * 0.03));
    col = mix(col, col * 0.7 + irid * float3(0.3, 0.6, 0.65) * 0.5, 0.35);
    float rays = pow(max(0.0, sin((p.x * 0.8 + p.y * 1.0) * 9.0 + q.y * 5.0 + t * 0.25)), 10.0) * smoothstep(-0.6, 0.7, p.y);
    col += sun * rays * (0.12 + uEnergy * 0.3 + uHeat * 0.25);
    col = mix(col, col + sun * 0.35, smoothstep(0.7, 1.0, r + q.x * 0.25) * uHeat);
    float d = length(p * float2(0.9, 0.7));
    col += birch * uGen * 0.12 * (0.5 + 0.5 * sin(t * 3.0 + d * 9.0));
    col += birch * uListen * 0.08 * exp(-length(p - float2(0.0, 0.42)) * 2.5);
    col *= 0.88 + 0.25 * uEnergy;
    col *= 1.0 - 0.12 * d * d;
    col += (h(uv * uRes + t) - 0.5) * 0.04;
    return half4(half3(col), 1.0);
}
"""

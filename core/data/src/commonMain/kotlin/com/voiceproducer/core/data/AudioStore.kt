package com.voiceproducer.core.data

import com.voiceproducer.core.dsp.WavCodec
import com.voiceproducer.core.model.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

class AudioStore(
    private val fs: FileSystem,
    private val dir: Path,
) {
    suspend fun write(
        name: String,
        audio: PcmAudio,
    ): String =
        withContext(Dispatchers.IO) {
            fs.createDirectories(dir)
            fs.write(dir / name) { write(WavCodec.encode(audio)) }
            name
        }

    suspend fun read(name: String): PcmAudio =
        withContext(Dispatchers.IO) {
            WavCodec.decode(fs.read(dir / name) { readByteArray() })
        }

    suspend fun delete(name: String) =
        withContext(Dispatchers.IO) {
            fs.delete(dir / name, mustExist = false)
        }

    suspend fun list(): List<Pair<String, Long?>> =
        withContext(Dispatchers.IO) {
            fs.listOrNull(dir).orEmpty().map { it.name to fs.metadataOrNull(it)?.lastModifiedAtMillis }
        }
}

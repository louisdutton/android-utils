package digital.dutton.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class AudioRecorder(private val cacheDir: File) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var outputFile: File? = null
    private var fileOutputStream: FileOutputStream? = null
    private var totalBytesWritten = 0

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    suspend fun startRecording(): Unit = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalStateException("Invalid buffer size: $bufferSize")
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = recorder
        outputFile = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
        val file = outputFile!!
        totalBytesWritten = 0

        val fos = FileOutputStream(file)
        fileOutputStream = fos

        // Write WAV header placeholder (will update later with actual size)
        writeWavHeader(fos, 0)

        recorder.startRecording()
        isRecording = true

        val buffer = ByteArray(bufferSize)

        while (isRecording && coroutineContext.isActive) {
            val bytesRead = recorder.read(buffer, 0, bufferSize)
            if (bytesRead > 0) {
                fos.write(buffer, 0, bytesRead)
                totalBytesWritten += bytesRead
            }
        }

        // Cleanup when loop exits
        fos.close()
        fileOutputStream = null
        recorder.stop()
        recorder.release()
        audioRecord = null

        // Update WAV header with actual data size
        updateWavHeader(file, totalBytesWritten)
    }

    fun stopRecording(): File? {
        isRecording = false
        return outputFile
    }

    fun isRecording() = isRecording

    private fun writeWavHeader(out: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + 36
        val header = ByteArray(44)

        // RIFF chunk
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // File size - 8
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()

        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1 size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // Audio format (1 = PCM)
        header[20] = 1
        header[21] = 0

        // Num channels (1 = mono)
        header[22] = 1
        header[23] = 0

        // Sample rate
        header[24] = (SAMPLE_RATE and 0xff).toByte()
        header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
        header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
        header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()

        // Byte rate (SampleRate * NumChannels * BitsPerSample/8)
        val byteRate = SAMPLE_RATE * 1 * 16 / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        // Block align (NumChannels * BitsPerSample/8)
        header[32] = 2
        header[33] = 0

        // Bits per sample
        header[34] = 16
        header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        out.write(header)
    }

    private fun updateWavHeader(file: File, dataSize: Int) {
        val raf = RandomAccessFile(file, "rw")

        // Update file size at position 4
        val totalSize = dataSize + 36
        raf.seek(4)
        raf.write(totalSize and 0xff)
        raf.write((totalSize shr 8) and 0xff)
        raf.write((totalSize shr 16) and 0xff)
        raf.write((totalSize shr 24) and 0xff)

        // Update data size at position 40
        raf.seek(40)
        raf.write(dataSize and 0xff)
        raf.write((dataSize shr 8) and 0xff)
        raf.write((dataSize shr 16) and 0xff)
        raf.write((dataSize shr 24) and 0xff)

        raf.close()
    }
}

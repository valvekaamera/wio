package ee.evitec.tahti.fnol.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Minimal RIFF/WAVE (PCM) writer. */
public final class WavWriter {

    private static final int HEADER_SIZE = 44;

    private WavWriter() {
    }

    public static byte[] header(PcmFormat format, int pcmBytes) {
        var buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        int blockAlign = format.channels() * format.bitsPerSample() / 8;
        buf.put("RIFF".getBytes())
           .putInt(36 + pcmBytes)
           .put("WAVE".getBytes())
           .put("fmt ".getBytes())
           .putInt(16)                          // PCM fmt chunk size
           .putShort((short) 1)                 // audio format = PCM
           .putShort((short) format.channels())
           .putInt(format.sampleRate())
           .putInt(format.sampleRate() * blockAlign)
           .putShort((short) blockAlign)
           .putShort((short) format.bitsPerSample())
           .put("data".getBytes())
           .putInt(pcmBytes);
        return buf.array();
    }

    public static byte[] toWav(PcmFormat format, byte[] pcm) {
        byte[] header = header(format, pcm.length);
        byte[] out = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(pcm, 0, out, header.length, pcm.length);
        return out;
    }

    public static void write(Path target, PcmFormat format, byte[] pcm) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, toWav(format, pcm));
    }

    /** Wraps an existing raw PCM file into a WAV file without loading it fully into memory. */
    public static void wrapPcmFile(Path rawPcm, Path target, PcmFormat format) throws IOException {
        long size = Files.size(rawPcm);
        if (size > Integer.MAX_VALUE - HEADER_SIZE) {
            throw new IOException("PCM file too large for WAV: " + size);
        }
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target); InputStream in = Files.newInputStream(rawPcm)) {
            out.write(header(format, (int) size));
            in.transferTo(out);
        }
    }
}

package ee.evitec.tahti.fnol.audio;

/**
 * Raw PCM format announced by the client in the {@code start} message.
 * Only {@code pcm_s16le} mono is supported today; the record keeps the contract
 * open for other encodings later without changing the wire format.
 */
public record PcmFormat(String encoding, int sampleRate, int channels) {

    public static final String PCM_S16LE = "pcm_s16le";

    public static PcmFormat defaultFormat(int sampleRate) {
        return new PcmFormat(PCM_S16LE, sampleRate, 1);
    }

    public PcmFormat withDefaults(int defaultSampleRate) {
        return new PcmFormat(
                encoding == null || encoding.isBlank() ? PCM_S16LE : encoding,
                sampleRate > 0 ? sampleRate : defaultSampleRate,
                channels > 0 ? channels : 1);
    }

    public void validate() {
        if (!PCM_S16LE.equals(encoding)) {
            throw new IllegalArgumentException("unsupported encoding: " + encoding);
        }
        if (channels != 1) {
            throw new IllegalArgumentException("unsupported channel count: " + channels);
        }
        if (sampleRate < 8_000 || sampleRate > 48_000) {
            throw new IllegalArgumentException("unsupported sample rate: " + sampleRate);
        }
    }

    public int bitsPerSample() {
        return 16;
    }

    public int bytesPerSecond() {
        return sampleRate * channels * (bitsPerSample() / 8);
    }

    public long durationMillis(long pcmBytes) {
        return pcmBytes * 1000L / bytesPerSecond();
    }
}

package digital.dutton.essentials.scores;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import java.nio.FloatBuffer;
import java.util.HashMap;

public final class Staff2ScoreModel implements AutoCloseable {
    private static final int NUM_CACHE_LAYERS = 32;
    private static final int CACHE_HEADS = 8;
    private static final int CACHE_DIM = 64;
    private static final int MAX_SEQ_LEN = 608;

    private OrtEnvironment env;
    private LiteRtModel encoder;
    private OrtSession decoder;

    public void load(String encoderPath, String decoderPath, int threads) throws Exception {
        encoder = new LiteRtModel();
        encoder.load(encoderPath, threads);

        env = OrtEnvironment.getEnvironment();
        SessionOptions options = new SessionOptions();
        HashMap<String, String> xnnpack = new HashMap<>();
        xnnpack.put("intra_op_num_threads", Integer.toString(threads));
        options.addXnnpack(xnnpack);
        decoder = env.createSession(decoderPath, options);
        options.close();
    }

    public long[][] run(FloatBuffer image) throws Exception {
        float[] context = encoder.runFloat(image);
        return decode(context);
    }

    private long[][] decode(float[] contextValues) throws OrtException {
        long outRhythm = 1;
        long outPitch = 0;
        long outLift = 0;
        long outArticulations = 0;

        OnnxTensor context = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(contextValues),
                new long[] {1, 1280, 512}
        );
        OnnxTensor emptyContext = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(new float[0]),
                new long[] {1, 0, 512}
        );

        String[] cacheInputNames = new String[NUM_CACHE_LAYERS];
        String[] cacheOutputNames = new String[NUM_CACHE_LAYERS];
        for (int i = 0; i < NUM_CACHE_LAYERS; i++) {
            cacheInputNames[i] = "cache_in" + i;
            cacheOutputNames[i] = "cache_out" + i;
        }

        OnnxTensor[] cache = new OnnxTensor[NUM_CACHE_LAYERS];
        for (int i = 0; i < NUM_CACHE_LAYERS; i++) {
            cache[i] = OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(new float[0]),
                    new long[] {1, CACHE_HEADS, 0, CACHE_DIM}
            );
        }

        long[][] tokenOutput = new long[5][MAX_SEQ_LEN];
        OrtSession.Result previousResult = null;

        try {
            for (int step = 0; step < MAX_SEQ_LEN; step++) {
                OnnxTensor xRhythm = OnnxTensor.createTensor(env, new long[][] {{outRhythm}});
                OnnxTensor xLift = OnnxTensor.createTensor(env, new long[][] {{outLift}});
                OnnxTensor xPitch = OnnxTensor.createTensor(env, new long[][] {{outPitch}});
                OnnxTensor xArticulations = OnnxTensor.createTensor(env, new long[][] {{outArticulations}});
                OnnxTensor xStep = OnnxTensor.createTensor(env, new long[] {step});

                HashMap<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("context", step == 0 ? context : emptyContext);
                inputs.put("lifts", xLift);
                inputs.put("pitchs", xPitch);
                inputs.put("rhythms", xRhythm);
                inputs.put("articulations", xArticulations);
                inputs.put("cache_len", xStep);
                for (int i = 0; i < NUM_CACHE_LAYERS; i++) {
                    inputs.put(cacheInputNames[i], cache[i]);
                }

                OrtSession.Result result = decoder.run(inputs);

                if (previousResult != null) {
                    previousResult.close();
                    previousResult = null;
                    for (int i = 0; i < NUM_CACHE_LAYERS; i++) {
                        cache[i] = null;
                    }
                }

                OnnxTensor rhythms = (OnnxTensor) result.get("out_rhythms").get();
                OnnxTensor lifts = (OnnxTensor) result.get("out_lifts").get();
                OnnxTensor pitchs = (OnnxTensor) result.get("out_pitchs").get();
                OnnxTensor articulations = (OnnxTensor) result.get("out_articulations").get();
                OnnxTensor positions = (OnnxTensor) result.get("out_positions").get();

                outRhythm = ((long[]) rhythms.getValue())[0];
                outLift = ((long[]) lifts.getValue())[0];
                outPitch = ((long[]) pitchs.getValue())[0];
                outArticulations = ((long[]) articulations.getValue())[0];
                long outPosition = ((long[]) positions.getValue())[0];

                tokenOutput[0][step] = outRhythm + 1;
                tokenOutput[1][step] = outLift + 1;
                tokenOutput[2][step] = outPitch + 1;
                tokenOutput[3][step] = outArticulations + 1;
                tokenOutput[4][step] = outPosition + 1;

                xRhythm.close();
                xLift.close();
                xPitch.close();
                xArticulations.close();
                xStep.close();

                if (outRhythm == 2) {
                    result.close();
                    break;
                }

                for (int i = 0; i < NUM_CACHE_LAYERS; i++) {
                    if (cache[i] != null) {
                        cache[i].close();
                    }
                    cache[i] = (OnnxTensor) result.get(cacheOutputNames[i]).get();
                }
                previousResult = result;
            }
        } finally {
            context.close();
            emptyContext.close();
            if (previousResult != null) {
                previousResult.close();
            } else {
                for (OnnxTensor tensor : cache) {
                    if (tensor != null) {
                        tensor.close();
                    }
                }
            }
        }

        return tokenOutput;
    }

    @Override
    public void close() throws OrtException {
        if (encoder != null) {
            encoder.close();
            encoder = null;
        }
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }
}

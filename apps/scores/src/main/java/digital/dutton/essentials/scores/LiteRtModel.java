package digital.dutton.essentials.scores;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.CompiledModel.CpuOptions;
import com.google.ai.edge.litert.CompiledModel.Options;
import com.google.ai.edge.litert.TensorBuffer;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.List;

public final class LiteRtModel implements AutoCloseable {
    private CompiledModel model;
    private List<TensorBuffer> inputBuffers;
    private List<TensorBuffer> outputBuffers;

    public void load(String path, int threads) throws Exception {
        HashSet<Accelerator> accelerators = new HashSet<>();
        accelerators.add(Accelerator.CPU);
        Options options = new Options(accelerators);
        options.setCpuOptions(new CpuOptions(threads, null, null));
        model = CompiledModel.create(path, options);
        inputBuffers = model.createInputBuffers();
        outputBuffers = model.createOutputBuffers();
    }

    public long[] runInt(FloatBuffer image) throws Exception {
        writeInput(image);
        return outputBuffers.get(0).readLong();
    }

    public float[] runFloat(FloatBuffer image) throws Exception {
        writeInput(image);
        return outputBuffers.get(0).readFloat();
    }

    private void writeInput(FloatBuffer image) throws Exception {
        TensorBuffer input = inputBuffers.get(0);
        float[] values = new float[image.remaining()];
        image.get(values);
        input.writeFloat(values);
        model.run(inputBuffers, outputBuffers);
    }

    @Override
    public void close() {
        if (model != null) {
            model.close();
            model = null;
        }
        if (inputBuffers != null) {
            inputBuffers.clear();
            inputBuffers = null;
        }
        if (outputBuffers != null) {
            outputBuffers.clear();
            outputBuffers = null;
        }
    }
}

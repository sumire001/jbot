package com.bot.jone.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.bot.jone.config.ModelManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * bge-small-zh 本地向量推理服务。
 *
 * 链路：文本 → BgeTokenizer(WordPiece) → int64 张量 → ONNX Runtime 推理
 *       → last_hidden_state → 取 [CLS] 位(第 0 token) → L2 归一 → 512 维向量。
 *
 * BGE 检索约定：query 端需加指令前缀（{@link #QUERY_PREFIX}），doc 端不加；
 * 这对语义召回率影响显著，漏掉会明显掉召回。
 *
 * 输入/输出张量名随 ONNX 导出版本变化，这里按关键字动态匹配，避免硬编码导致
 * session.run 抛 InvalidArgument。
 */
@Slf4j
@Component
public class EmbeddingService {

    private static final String MODEL_KEY = "bge-small-zh";
    private static final String ONNX_FILE = "model_qint8.onnx";
    private static final String VOCAB_FILE = "vocab.txt";
    private static final int EMBED_DIM = 512;
    /** BGE 检索 query 指令前缀（doc 端不加）。 */
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";

    @Autowired
    private ModelManager modelManager;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private BgeTokenizer tokenizer;
    private volatile boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    public int getDim() {
        return EMBED_DIM;
    }

    @PostConstruct
    public void init() {
        if (modelManager == null) {
            // 非 Spring 环境（如 forPaths 构造）跳过
            return;
        }
        Path onnx = modelManager.resolveFile(MODEL_KEY, ONNX_FILE);
        Path vocab = modelManager.resolveFile(MODEL_KEY, VOCAB_FILE);
        if (onnx == null || !onnx.toFile().exists() || vocab == null || !vocab.toFile().exists()) {
            log.warn("[embedding] 模型文件缺失，EmbeddingService 未启用: onnx={} vocab={}", onnx, vocab);
            return;
        }
        try {
            load(onnx, vocab);
            log.info("[embedding] EmbeddingService 已加载 {}，输出维度={}", MODEL_KEY, EMBED_DIM);
        } catch (Exception e) {
            log.error("[embedding] 加载 {} 失败", MODEL_KEY, e);
        }
    }

    /** 直接按路径加载（供 Spring 与非 Spring 环境共用）。 */
    private void load(Path onnx, Path vocab) throws Exception {
        this.tokenizer = new BgeTokenizer(vocab);
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        this.ortSession = ortEnv.createSession(onnx.toString(), opts);
        this.ready = true;
    }

    /** 非 Spring 环境手动构造（测试 / 自检用）。 */
    public static EmbeddingService forPaths(Path onnx, Path vocab) throws Exception {
        EmbeddingService s = new EmbeddingService();
        s.load(onnx, vocab);
        return s;
    }

    /** 文档向量（不加前缀）。 */
    public float[] embed(String text) {
        return embedInternal(text);
    }

    /** 检索 query 向量（加 BGE 指令前缀）。 */
    public float[] embedQuery(String text) {
        return embedInternal(QUERY_PREFIX + text);
    }

    private float[] embedInternal(String text) {
        if (!ready) throw new IllegalStateException("EmbeddingService 未就绪（模型未加载）");
        int[] ids = tokenizer.encode(text);
        int len = ids.length;
        long[] inputIds = new long[len];
        long[] attn = new long[len];
        long[] typeIds = new long[len];
        for (int i = 0; i < len; i++) {
            inputIds[i] = ids[i];
            attn[i] = 1L;
            typeIds[i] = 0L;
        }

        Set<String> inputNames = ortSession.getInputNames();
        Set<String> outputNames = ortSession.getOutputNames();
        String idName = matchName(inputNames, "input_ids");
        String attName = matchName(inputNames, "attention_mask");
        String ttName = matchName(inputNames, "token_type_ids");
        String matchedOut = matchName(outputNames, "last_hidden_state");
        final String outName = (matchedOut != null)
                ? matchedOut
                : (outputNames.isEmpty() ? null : outputNames.iterator().next());

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor tIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), new long[]{1, len});
             OnnxTensor tAtt = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attn), new long[]{1, len})) {
            inputs.put(idName, tIds);
            inputs.put(attName, tAtt);
            if (ttName != null) {
                try (OnnxTensor tType = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(typeIds), new long[]{1, len})) {
                    inputs.put(ttName, tType);
                }
            }
            try (OrtSession.Result result = ortSession.run(inputs)) {
                OnnxValue outValue = result.get(outName)
                        .orElseThrow(() -> new IllegalStateException("ONNX 输出缺失: " + outName));
                float[] vec = extractClsVector(((OnnxTensor) outValue).getValue());
                return l2Normalize(vec);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("ONNX 推理失败", e);
        }
    }

    /** 从输出张量取 [CLS] 位向量：兼容 3D([1,seq,hidden]) 或已池化 2D([1,hidden])。 */
    private static float[] extractClsVector(Object value) {
        if (value instanceof float[][][]) {
            return ((float[][][]) value)[0][0];
        } else if (value instanceof float[][]) {
            return ((float[][]) value)[0];
        }
        throw new IllegalStateException("意外的 ONNX 输出形状（非 float 张量）");
    }

    private static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    /** 按关键字匹配张量名（小写包含匹配），找不到返回 null。 */
    private static String matchName(Set<String> names, String keyword) {
        String k = keyword.toLowerCase();
        for (String n : names) {
            if (n.toLowerCase().contains(k)) return n;
        }
        return null;
    }

    /** 自检：跑一条文本，返回就绪/维度/L2模长/耗时。 */
    public Map<String, Object> selfTest(String sample) {
        Map<String, Object> r = new HashMap<>();
        r.put("ready", ready);
        r.put("dim", EMBED_DIM);
        if (!ready) return r;
        long t0 = System.nanoTime();
        float[] v = embed(sample);
        long t1 = System.nanoTime();
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        r.put("actualDim", v.length);
        r.put("l2norm", Math.sqrt(norm));
        r.put("elapsedMs", (t1 - t0) / 1_000_000.0);
        r.put("first8", firstN(v, 8));
        return r;
    }

    private static List<Float> firstN(float[] v, int n) {
        List<Float> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, v.length); i++) out.add(v[i]);
        return out;
    }

    @PreDestroy
    public void close() {
        if (ortSession != null) try { ortSession.close(); } catch (Exception ignored) {}
        if (ortEnv != null) try { ortEnv.close(); } catch (Exception ignored) {}
        ready = false;
    }

    /** 独立自检入口：java -cp ... com.bot.jone.embedding.EmbeddingService [模型目录] [文本] */
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length > 0 ? args[0] : "data/models/bge-small-zh");
        String text = args.length > 1 ? args[1] : "你好，世界";
        EmbeddingService s = forPaths(base.resolve(ONNX_FILE), base.resolve(VOCAB_FILE));
        Map<String, Object> r = s.selfTest(text);
        System.out.println("=== bge-small-zh 自检 ===");
        System.out.println("ready      : " + r.get("ready"));
        System.out.println("expectedDim: " + r.get("dim"));
        System.out.println("actualDim  : " + r.get("actualDim"));
        System.out.println("l2norm     : " + r.get("l2norm"));
        System.out.println("elapsedMs  : " + r.get("elapsedMs"));
        System.out.println("first8     : " + r.get("first8"));
        System.out.println("===================");
    }
}

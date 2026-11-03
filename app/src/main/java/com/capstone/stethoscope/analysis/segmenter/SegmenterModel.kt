package com.capstone.stethoscope.analysis.segmenter

import java.io.InputStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The algorithm team's CNN, loaded from their `model.json` and run by hand.
 *
 * A port of `forwardPass` in `segmentHeartSound.m` (see `algorithm/segmenter/`). No
 * inference runtime: 55k parameters and five layer types do not justify TFLite's
 * size, and a hand-written pass is one the team can read line-for-line against their
 * MATLAB.
 *
 * Layouts, all taken from what the MATLAB *does* rather than from the prose in the JSON
 * (the two disagree for conv weights — the MATLAB is the verified one):
 *  - conv weights: MATLAB `reshape(w, k, Cin, Cout)`, so `w[j + k*(ci + Cin*co)]`.
 *    Cross-correlation (no kernel flip), zero "same" padding, stride 1.
 *  - dense weights: `reshape(w, inputs, outputs)'`, so `W[o][i] = w[i + inputs*o]`.
 *  - activations are stored channel-major, `a[c*T + t]`, which is exactly MATLAB's
 *    column-major flatten of its `[time x channel]` array — so "flatten" is a no-op.
 *  - dropout is identity at inference and is skipped.
 *
 * Runs in single precision, like the trained network.
 */
class SegmenterModel private constructor(
    val inputLength: Int,
    val classes: List<String>,
    private val layers: List<Layer>,
) {

    sealed interface Layer

    /** Conv followed (optionally) by batch-norm and ReLU, fused into one pass. */
    class Conv(
        val kernel: Int,
        val cin: Int,
        val cout: Int,
        val weights: FloatArray,
        val bias: FloatArray,
    ) : Layer {
        // Batch-norm folded in as y*scale + shift; identity until a batchnorm layer follows.
        var bnScale = FloatArray(cout) { 1f }
        var bnShift = FloatArray(cout)
        var relu = false
    }

    class MaxPool(val pool: Int, val stride: Int) : Layer

    class Dense(val inputs: Int, val outputs: Int, val weights: FloatArray, val bias: FloatArray) : Layer {
        var relu = false
    }

    object Softmax : Layer

    /**
     * Scratch buffers for one forward pass. Not thread-safe: give each worker its own,
     * which is what [HeartSoundSegmenter] does.
     */
    inner class Workspace {
        private val maxSize: Int = run {
            var t = inputLength
            var c = 1
            var biggest = t
            for (l in layers) when (l) {
                is Conv -> { c = l.cout; biggest = max(biggest, t * c) }
                is MaxPool -> t = (t - l.pool) / l.stride + 1
                is Dense -> { t = 1; c = l.outputs; biggest = max(biggest, c) }
                Softmax -> Unit
            }
            biggest
        }
        internal val a = FloatArray(maxSize)
        internal val b = FloatArray(maxSize)
    }

    /**
     * One window of [inputLength] samples, read from [x] starting at [offset], into
     * [out] (one probability per class, in [classes] order).
     */
    fun forward(x: FloatArray, offset: Int, ws: Workspace, out: FloatArray) {
        var cur = ws.a
        var nxt = ws.b
        System.arraycopy(x, offset, cur, 0, inputLength)
        var t = inputLength
        var c = 1
        for (l in layers) {
            when (l) {
                is Conv -> {
                    conv(cur, t, l, nxt)
                    c = l.cout
                }
                is MaxPool -> {
                    val tOut = (t - l.pool) / l.stride + 1
                    maxPool(cur, t, c, l, tOut, nxt)
                    t = tOut
                }
                is Dense -> {
                    dense(cur, l, nxt)
                    t = 1
                    c = l.outputs
                }
                Softmax -> {
                    softmax(cur, c)
                    continue // in place
                }
            }
            val tmp = cur; cur = nxt; nxt = tmp
        }
        System.arraycopy(cur, 0, out, 0, out.size)
    }

    private fun conv(x: FloatArray, t: Int, l: Conv, y: FloatArray) {
        val k = l.kernel
        val pad = (k - 1) / 2
        val w = l.weights
        for (co in 0 until l.cout) {
            val yo = co * t
            val b = l.bias[co]
            for (i in 0 until t) y[yo + i] = b
            for (ci in 0 until l.cin) {
                val xo = ci * t
                for (j in 0 until k) {
                    val wv = w[j + k * (ci + l.cin * co)]
                    if (wv == 0f) continue
                    // y[i] += wv * x[i + j - pad], skipping the zero padding.
                    val shift = j - pad
                    val lo = max(0, -shift)
                    val hi = minOf(t, t - shift)
                    var i = lo
                    var xi = xo + lo + shift
                    var yi = yo + lo
                    while (i < hi) {
                        y[yi] += wv * x[xi]
                        i++; xi++; yi++
                    }
                }
            }
            val s = l.bnScale[co]
            val sh = l.bnShift[co]
            if (l.relu) {
                for (i in yo until yo + t) {
                    val v = y[i] * s + sh
                    y[i] = if (v > 0f) v else 0f
                }
            } else {
                for (i in yo until yo + t) y[i] = y[i] * s + sh
            }
        }
    }

    private fun maxPool(x: FloatArray, t: Int, c: Int, l: MaxPool, tOut: Int, y: FloatArray) {
        for (ch in 0 until c) {
            val xo = ch * t
            val yo = ch * tOut
            for (i in 0 until tOut) {
                val s = xo + i * l.stride
                var m = x[s]
                for (j in 1 until l.pool) if (x[s + j] > m) m = x[s + j]
                y[yo + i] = m
            }
        }
    }

    private fun dense(x: FloatArray, l: Dense, y: FloatArray) {
        val n = l.inputs
        for (o in 0 until l.outputs) {
            var acc = l.bias[o]
            val wo = n * o
            for (i in 0 until n) acc += l.weights[wo + i] * x[i]
            y[o] = if (l.relu && acc < 0f) 0f else acc
        }
    }

    private fun softmax(x: FloatArray, n: Int) {
        var m = x[0]
        for (i in 1 until n) if (x[i] > m) m = x[i]
        var sum = 0f
        for (i in 0 until n) { x[i] = exp(x[i] - m); sum += x[i] }
        for (i in 0 until n) x[i] /= sum
    }

    companion object {
        /** Parses the team's `model.json`, folding batch-norm and ReLU into the layer before. */
        fun load(input: InputStream): SegmenterModel =
            fromJson(input.bufferedReader().use { it.readText() })

        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): SegmenterModel {
            val m = MiniJson.parse(text) as Map<String, Any?>
            val classes = m.list("classes").map { it as String }
            val out = ArrayList<Layer>()
            for (raw in m.list("layers")) {
                val l = raw as Map<String, Any?>
                when (val type = l.str("type")) {
                    "input", "flatten", "dropout" -> Unit
                    "conv1d" -> {
                        require(l.int("stride") == 1) { "conv stride ${l.int("stride")} unsupported" }
                        require(l.int("kernel") % 2 == 1) { "even conv kernel unsupported" }
                        out += Conv(
                            kernel = l.int("kernel"),
                            cin = l.int("inChannels"),
                            cout = l.int("outChannels"),
                            weights = l.floats("weights"),
                            bias = l.floats("bias"),
                        )
                    }
                    "batchnorm" -> {
                        val conv = out.lastOrNull() as? Conv
                            ?: error("batchnorm must follow a conv layer")
                        val eps = l.num("epsilon")
                        val scale = l.doubles("scale")
                        val offset = l.doubles("offset")
                        val mean = l.doubles("mean")
                        val variance = l.doubles("variance")
                        // (x - mean)/sqrt(var+eps)*scale + offset  ==  x*s + sh
                        conv.bnScale = FloatArray(conv.cout) { (scale[it] / sqrt(variance[it] + eps)).toFloat() }
                        conv.bnShift = FloatArray(conv.cout) {
                            (offset[it] - mean[it] * scale[it] / sqrt(variance[it] + eps)).toFloat()
                        }
                    }
                    "relu" -> when (val prev = out.lastOrNull()) {
                        is Conv -> prev.relu = true
                        is Dense -> prev.relu = true
                        else -> error("relu must follow a conv or dense layer")
                    }
                    "maxpool1d" -> out += MaxPool(l.int("pool"), l.int("stride"))
                    "dense" -> out += Dense(
                        inputs = l.int("inputs"),
                        outputs = l.int("outputs"),
                        weights = l.floats("weights"),
                        bias = l.floats("bias"),
                    )
                    "softmax" -> out += Softmax
                    else -> error("Unknown layer type '$type' in model.json")
                }
            }
            return SegmenterModel(m.int("inputLength"), classes, out)
        }
    }
}

/*
 * Copyright 2026 gongfpp (https://github.com/gongfpp/whatsBird)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.whatsbird.classify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.whatsbird.species.SpeciesDictionary
import com.whatsbird.util.BitmapOps
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single class score out of the classifier's output vector. */
data class Prediction(val classIndex: Int, val score: Float)

/**
 * Runs the bundled bird-species classifier over a cropped bird box.
 *
 * The model, its input geometry and its preprocessing constants all come from the same generated
 * asset (`species.json`), so the app cannot silently disagree with whatever `ml/` trained.
 */
class SpeciesClassifier private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int,
    private val inputType: DataType,
    private val outputType: DataType,
    private val outputCount: Int,
    private val inputScale: Float,
    private val inputOffset: Float,
    private val outputScale: Float,
    private val outputZeroPoint: Int,
) : Closeable {

    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private var pixels = IntArray(inputSize * inputSize)
    private var scratch: Bitmap? = null

    init {
        val inTensor: Tensor = interpreter.getInputTensor(0)
        val outTensor: Tensor = interpreter.getOutputTensor(0)
        inputBuffer = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).order(ByteOrder.nativeOrder())
        interpreter.allocateTensors()
    }

    /**
     * Returns up to [topK] predictions, best first. Never throws; failures come back as
     * [ClassificationResult.Failure] instead of an empty list, so "the model errored" cannot be
     * confused with "the model was unsure".
     *
     * Serialised on the instance: the live preview classifies on the pipeline's own thread while a
     * shutter press re-identifies the still on the capture thread. The [Interpreter], its direct
     * buffers and the scratch bitmap are all single-threaded resources, and a concurrent `run` is a
     * native abort — no `runCatching` can intercept that.
     */
    @Synchronized
    fun classify(bitmap: Bitmap, topK: Int = TOP_K): ClassificationResult {
        if (bitmap.width <= 0 || bitmap.height <= 0) return ClassificationResult.Unknown
        return runCatching { ClassificationResult.of(topKOf(inferScores(bitmap), topK)) }
            .onFailure { Log.w(TAG, "classification failed", it) }
            .getOrElse { ClassificationResult.Failure(it) }
    }

    /**
     * Flip-augmented classification for still photos: runs the crop and its mirror and averages the
     * two score vectors.
     *
     * Costs one extra inference, which the preview cannot afford but a shutter press can — and it is
     * the still photo whose names the user reads afterwards. Averaging also sharpens the score
     * distribution, so a correct species clears the display threshold more often instead of landing
     * just under it and being reported as "bird, species unknown".
     */
    @Synchronized
    fun classifyAveraged(bitmap: Bitmap, topK: Int = TOP_K): ClassificationResult {
        if (bitmap.width <= 0 || bitmap.height <= 0) return ClassificationResult.Unknown
        return runCatching {
            val scores = inferScores(bitmap)
            val mirrored = BitmapOps.mirror(bitmap)
            if (mirrored != null) {
                try {
                    val flipped = inferScores(mirrored)
                    for (i in scores.indices) scores[i] = (scores[i] + flipped[i]) / 2f
                } finally {
                    mirrored.recycle()
                }
            }
            ClassificationResult.of(topKOf(scores, topK))
        }.onFailure { Log.w(TAG, "averaged classification failed", it) }
            .getOrElse { ClassificationResult.Failure(it) }
    }

    private fun inferScores(bitmap: Bitmap): FloatArray {
        val square = toSquare(bitmap)
        fillInput(square)
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        return readScores()
    }

    /** Centre-crops to a square then scales, so the bird is not squashed by the resize. */
    private fun toSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val cropped = if (side == bitmap.width && side == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, left, top, side, side)
        }
        val scaled = if (side == inputSize) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, inputSize, inputSize, true)
        }
        if (cropped !== bitmap && cropped !== scaled) cropped.recycle()
        val previous = scratch
        scratch = scaled
        if (previous != null && previous !== scaled) previous.recycle()
        return scaled
    }

    private fun fillInput(bitmap: Bitmap) {
        if (pixels.size != inputSize * inputSize) pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        inputBuffer.rewind()

        when (inputType) {
            DataType.FLOAT32 -> {
                val floats = inputBuffer.asFloatBuffer()
                var i = 0
                for (p in pixels) {
                    floats.put(i++, Color.red(p) * inputScale + inputOffset)
                    floats.put(i++, Color.green(p) * inputScale + inputOffset)
                    floats.put(i++, Color.blue(p) * inputScale + inputOffset)
                }
            }
            DataType.UINT8 -> {
                var i = 0
                for (p in pixels) {
                    inputBuffer.put(i++, Color.red(p).toByte())
                    inputBuffer.put(i++, Color.green(p).toByte())
                    inputBuffer.put(i++, Color.blue(p).toByte())
                }
            }
            DataType.INT8 -> {
                // Legacy int8 input: fold the scale/zero-point the converter baked in.
                var i = 0
                for (p in pixels) {
                    inputBuffer.put(i++, quantize(Color.red(p)))
                    inputBuffer.put(i++, quantize(Color.green(p)))
                    inputBuffer.put(i++, quantize(Color.blue(p)))
                }
            }
            else -> error("unsupported input type $inputType")
        }
    }

    private fun quantize(value: Int): Byte = ((value / inputScale) + inputOffset).toInt().toByte()

    private fun readScores(): FloatArray {
        val scores = FloatArray(outputCount)
        outputBuffer.rewind()
        when (outputType) {
            DataType.FLOAT32 -> outputBuffer.asFloatBuffer().get(scores)
            DataType.UINT8 -> for (i in 0 until outputCount) {
                scores[i] = ((outputBuffer.get(i).toInt() and 0xFF) - outputZeroPoint) * outputScale
            }
            DataType.INT8 -> for (i in 0 until outputCount) {
                scores[i] = (outputBuffer.get(i).toInt() - outputZeroPoint) * outputScale
            }
            else -> error("unsupported output type $outputType")
        }

        // The exported graph ends in softmax, but a sum far from 1 means we were handed logits.
        var sum = 0f
        for (s in scores) sum += s
        if (sum < 0.9f || sum > 1.1f) {
            var max = Float.NEGATIVE_INFINITY
            for (s in scores) if (s > max) max = s
            var expSum = 0f
            for (i in scores.indices) {
                scores[i] = kotlin.math.exp(scores[i] - max)
                expSum += scores[i]
            }
            if (expSum > 0f) for (i in scores.indices) scores[i] /= expSum
        }
        return scores
    }

    private fun topKOf(scores: FloatArray, topK: Int): List<Prediction> = scores.indices
        .sortedByDescending { scores[it] }
        .take(topK)
        .map { Prediction(it, scores[it]) }

    @Synchronized
    override fun close() {
        runCatching { interpreter.close() }
        scratch = null
    }

    companion object {
        private const val TAG = "SpeciesClassifier"
        const val ASSET_PATH = "models/bird_classifier.tflite"
        const val TOP_K = 5

        /** Returns null when the asset is missing or unusable — callers degrade to detection-only. */
        fun create(context: Context, dictionary: SpeciesDictionary?): SpeciesClassifier? {
            return runCatching {
                val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
                val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                model.put(bytes)
                model.rewind()

                val interpreter = createInterpreter(model)
                val inTensor = interpreter.getInputTensor(0)
                val outTensor = interpreter.getOutputTensor(0)
                val inputSize = inTensor.shape().let { shape ->
                    // Accept NHWC [1,H,W,3]; fall back to the dictionary's declared size.
                    if (shape.size == 4 && shape[1] == shape[2]) shape[1] else dictionary?.inputSize ?: 224
                }
                val outQuant = outTensor.quantizationParams()
                val inQuant = inTensor.quantizationParams()
                val count = outTensor.numElements()

                SpeciesClassifier(
                    interpreter = interpreter,
                    inputSize = inputSize,
                    inputType = inTensor.dataType(),
                    outputType = outTensor.dataType(),
                    outputCount = count,
                    inputScale = dictionary?.inputScale ?: inQuant.scale.takeIf { it > 0f } ?: (1f / 127.5f),
                    inputOffset = dictionary?.inputOffset ?: inQuant.zeroPoint.toFloat(),
                    outputScale = outQuant.scale.takeIf { it > 0f } ?: 1f,
                    outputZeroPoint = outQuant.zeroPoint,
                ).also {
                    Log.i(TAG, "classifier ready: input=${inTensor.dataType()} ${inputSize}px, classes=$count")
                }
            }.onFailure { Log.w(TAG, "classifier unavailable; running detection-only", it) }.getOrNull()
        }

        private const val RUNTIME_THREADS = 2

        private fun options(useXnnpack: Boolean) = Interpreter.Options().apply {
            setNumThreads(RUNTIME_THREADS)
            setUseXNNPACK(useXnnpack)
        }

        /**
         * Builds the interpreter, retrying on the plain CPU kernels if XNNPACK refuses the graph.
         *
         * The delegate rejects some fully-quantised MobileNetV3 builds at prepare time. That used to
         * surface as "classifier unavailable", i.e. the app silently dropped to detection-only, when
         * the model was fine and only the delegate was not. Slower inference is a much better
         * outcome than no identification at all.
         */
        private fun createInterpreter(model: ByteBuffer): Interpreter {
            runCatching {
                model.rewind()
                val accelerated = Interpreter(model, options(useXnnpack = true))
                accelerated.allocateTensors()
                Log.i(TAG, "interpreter ready (xnnpack=true)")
                return accelerated
            }.onFailure {
                Log.w(TAG, "XNNPACK delegate rejected the classifier; retrying on plain CPU kernels", it)
            }
            model.rewind()
            val plain = Interpreter(model, options(useXnnpack = false))
            plain.allocateTensors()
            Log.i(TAG, "interpreter ready (xnnpack=false)")
            return plain
        }
    }
}

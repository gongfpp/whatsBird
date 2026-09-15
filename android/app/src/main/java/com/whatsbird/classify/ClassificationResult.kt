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

/**
 * The outcome of one classification pass.
 *
 * Before this type existed, a runtime failure inside the interpreter collapsed into the same
 * "no predictions" result a legitimate pass produces, so a model that had blown up looked identical
 * to a model that was simply unsure. The three cases below separate them:
 *
 *  - [Success] — inference ran, scores are usable;
 *  - [Unknown] — inference ran but produced nothing usable (degenerate input, empty scores);
 *  - [Failure] — inference itself errored; no verdict exists.
 */
sealed interface ClassificationResult {

    /** The classifier ran cleanly; [predictions] is ordered best-first. */
    data class Success(val predictions: List<Prediction>) : ClassificationResult

    /** The classifier ran but produced no usable ranking. */
    data object Unknown : ClassificationResult

    /** The classifier failed at runtime; no verdict exists. */
    data class Failure(val error: Throwable) : ClassificationResult

    companion object {
        /** Maps an already-ranked prediction list (possibly empty) to the matching outcome. */
        fun of(predictions: List<Prediction>): ClassificationResult =
            if (predictions.isEmpty()) Unknown else Success(predictions)
    }
}

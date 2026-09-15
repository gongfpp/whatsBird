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

package com.whatsbird.detect

/**
 * The outcome of a synchronous still-image detection.
 *
 * Before this type existed, `detectSync` mapped a runtime failure and a clean "nothing found" to
 * the same empty list, so a photo with a broken detector looked exactly like a photo with no bird
 * in it. Keeping the three outcomes apart is what lets the caller tell "the model works and there
 * is no bird" from "the model just failed".
 */
sealed interface DetectionResult {

    /** The detector ran cleanly and found no bird in this frame. */
    data object NoBird : DetectionResult

    /** The detector ran cleanly; boxes are normalised to the frame it was produced from. */
    data class Success(val detections: List<RawDetection>) : DetectionResult

    /** The detector could not produce a verdict at all (runtime error or unavailable). */
    data class Failure(val error: Throwable) : DetectionResult

    /** Convenience for callers that only care about the boxes. */
    val detectionsOrNull: List<RawDetection>?
        get() = (this as? Success)?.detections
}

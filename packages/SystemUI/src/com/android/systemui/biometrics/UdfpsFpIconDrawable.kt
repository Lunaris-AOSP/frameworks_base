/*
 * Copyright (C) 2024-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.util.Log

/**
 * Draws udfps fingerprint if sensor isn't illuminating.
 */
class UdfpsFpIconDrawable(context: Context) : UdfpsIconDrawable(context) {
    
    companion object {
        private const val TAG = "UdfpsFpIconDrawable"
    }
    
    override fun draw(canvas: Canvas) {
        try {
            val udfpsDrawable = getUdfpsDrawable()
            udfpsDrawable?.apply {
                if (this is BitmapDrawable) {
                    val bitmap = this.bitmap
                    if (bitmap == null || bitmap.isRecycled) {
                        Log.w(TAG, "Skipping draw - bitmap is null or recycled")
                        return
                    }
                }
                
                setBounds(bounds)
                draw(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing UDFPS icon: ${e.message}", e)
        }
    }
    
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
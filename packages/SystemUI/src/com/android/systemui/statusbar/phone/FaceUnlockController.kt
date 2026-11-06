package com.android.systemui.statusbar.phone

class FaceUnlockController private constructor() {

    private var faceUnlockImageView: FaceUnlockImageView? = null

    fun setFaceUnlockView(view: FaceUnlockImageView) {
        faceUnlockImageView = view
    }

    fun clearFaceUnlockView(view: FaceUnlockImageView) {
        if (faceUnlockImageView === view) {
            faceUnlockImageView = null
        }
    }

    fun setBouncerState(state: FaceUnlockImageView.State) {
        val view = faceUnlockImageView ?: return
        view.postDelayed({
            if (faceUnlockImageView === view && view.isAttachedToWindow) {
                view.setState(state)
            }
        }, 100)
    }

    companion object {
        @Volatile
        private var instance: FaceUnlockController? = null

        fun getInstance(): FaceUnlockController {
            return instance ?: synchronized(this) {
                instance ?: FaceUnlockController().also { instance = it }
            }
        }
    }
}

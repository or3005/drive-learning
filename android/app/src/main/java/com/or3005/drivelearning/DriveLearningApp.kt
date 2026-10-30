package com.or3005.drivelearning

import android.app.Application

class DriveLearningApp : Application() {
    /** One player for the whole app, shared by the phone screen and the Android Auto screen. */
    val controller: LearningController by lazy { LearningController(this) }
}

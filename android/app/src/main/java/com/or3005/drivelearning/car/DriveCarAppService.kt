package com.or3005.drivelearning.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Android Auto entry point. */
class DriveCarAppService : CarAppService() {

    // A personal, sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Deprecated("Deprecated in Java")
    override fun onCreateSession(): Session = DriveSession()
}

class DriveSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = LessonScreen(carContext)
}

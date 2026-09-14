package com.whatsbird

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whatsbird.ui.CameraPermissionGate
import com.whatsbird.ui.CameraScreen
import com.whatsbird.ui.theme.WhatsBirdTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhatsBirdTheme {
                CameraPermissionGate {
                    CameraScreen()
                }
            }
        }
    }
}

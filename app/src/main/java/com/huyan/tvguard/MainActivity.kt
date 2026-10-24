package com.huyan.tvguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.huyan.tvguard.ui.GuardianRoute
import com.huyan.tvguard.ui.theme.TvEyeGuardianTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TvEyeGuardianTheme {
                GuardianRoute()
            }
        }
    }
}

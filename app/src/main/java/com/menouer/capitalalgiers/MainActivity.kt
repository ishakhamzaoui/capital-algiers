package com.menouer.capitalalgiers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.menouer.capitalalgiers.ui.CapitalAlgiersApp
import com.menouer.capitalalgiers.ui.theme.CapitalAlgiersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CapitalAlgiersTheme {
                CapitalAlgiersApp()
            }
        }
    }
}
package com.capstone.stethoscope

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.capstone.stethoscope.locale.LocaleHelper
import com.capstone.stethoscope.ui.navigation.AppNavHost
import com.capstone.stethoscope.ui.theme.StethoScopeTheme

class MainActivity : ComponentActivity() {

    /** Apply the user-selected language before any resources are resolved. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hand the teal launch theme back before the window is created.
        //
        // The manifest points this activity at Theme.StethoScope.Launch so the phone's
        // starting window — painted before Compose has a first frame — is already the
        // splash's teal instead of a near-white flash. That theme must not stick around
        // afterwards: its windowBackground sits behind every screen, so a teal window
        // would flash on activity recreation (the language switcher calls recreate()) and
        // show through anywhere a screen does not paint every pixel.
        //
        // Before super.onCreate, because that is where the window gets its background.
        setTheme(R.style.Theme_StethoScope)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StethoScopeTheme {
                AppNavHost()
            }
        }
    }
}

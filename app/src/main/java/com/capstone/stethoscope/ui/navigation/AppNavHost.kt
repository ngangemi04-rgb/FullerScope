package com.capstone.stethoscope.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capstone.stethoscope.StethoscopeApp
import com.capstone.stethoscope.ui.diagnostics.DiagnosticsScreen
import com.capstone.stethoscope.ui.library.LibraryScreen
import com.capstone.stethoscope.ui.listen.ListenScreen
import com.capstone.stethoscope.ui.result.ResultScreen
import com.capstone.stethoscope.ui.review.ReviewScreen
import com.capstone.stethoscope.ui.settings.SettingsScreen
import com.capstone.stethoscope.ui.splash.SplashScreen
import com.capstone.stethoscope.ui.testing.TestingScreen
import com.capstone.stethoscope.ui.tutorial.TutorialScreen

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as StethoscopeApp

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    // Dropped from the back stack so Back from Listen exits instead of
                    // replaying the startup screen. The tutorial only opens on request.
                    navController.navigate(Routes.LISTEN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.TUTORIAL) {
            // Opened from the Listen toolbar or Settings; finishing returns there.
            TutorialScreen(onFinished = { navController.popBackStack() })
        }

        composable(Routes.LISTEN) {
            // Recording happens on this screen rather than a separate one, so it reports
            // a finished recording instead of a navigation request.
            ListenScreen(
                onRecordingSaved = { id -> navController.navigate(Routes.review(id)) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenTesting = { navController.navigate(Routes.TESTING) },
                onOpenTutorial = { navController.navigate(Routes.TUTORIAL) },
            )
        }

        composable(
            route = Routes.REVIEW,
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L
            ReviewScreen(
                recordingId = id,
                // Retake returns to Listen, where recording now lives.
                onRetake = { navController.popBackStack(Routes.LISTEN, inclusive = false) },
                onAnalyze = {
                    navController.navigate(Routes.result(id)) {
                        popUpTo(Routes.LISTEN) { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L
            ResultScreen(
                recordingId = id,
                onDone = {
                    // Finished with this patient — clear the draft and return to the start.
                    app.container.session.reset()
                    navController.popBackStack(Routes.LISTEN, inclusive = false)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenResult = { id -> navController.navigate(Routes.result(id)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenTesting = { navController.navigate(Routes.TESTING) },
                onOpenTutorial = { navController.navigate(Routes.TUTORIAL) },
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.TESTING) {
            TestingScreen(onBack = { navController.popBackStack() })
        }
    }
}

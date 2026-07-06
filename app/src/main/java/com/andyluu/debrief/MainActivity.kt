package com.andyluu.debrief

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andyluu.debrief.ui.AppViewModel
import com.andyluu.debrief.ui.DebriefTheme
import com.andyluu.debrief.ui.GlobalSearchScreen
import com.andyluu.debrief.ui.LibraryScreen
import com.andyluu.debrief.ui.ReviewScreen
import com.andyluu.debrief.ui.ReviewViewModel
import com.andyluu.debrief.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DebriefTheme {
                val nav = rememberNavController()
                val context = LocalContext.current
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                    appViewModel.linkFolder(uri)
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }

                LaunchedEffect(Unit) { appViewModel.scan() }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appViewModel.scan() }

                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            viewModel = appViewModel,
                            onPickFolder = { folderPicker.launch(null) },
                            onOpenRecording = { id -> nav.navigate("review/$id?at=0") },
                            onOpenSearch = { nav.navigate("search") },
                            onOpenSettings = { nav.navigate("settings") },
                            onRequestTranscription = { id ->
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                appViewModel.transcribe(id)
                            },
                        )
                    }
                    composable("search") {
                        GlobalSearchScreen(
                            viewModel = appViewModel,
                            onBack = nav::popBackStack,
                            onOpenHit = { hit -> nav.navigate("review/${hit.recordingId}?at=${hit.timestampMs}") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(appViewModel, nav::popBackStack)
                    }
                    composable(
                        route = "review/{recordingId}?at={timestamp}",
                        arguments = listOf(
                            navArgument("recordingId") { type = NavType.StringType },
                            navArgument("timestamp") { type = NavType.LongType; defaultValue = 0L },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("recordingId") ?: return@composable
                        val timestamp = entry.arguments?.getLong("timestamp") ?: 0L
                        val reviewViewModel: ReviewViewModel = viewModel(
                            key = id,
                            factory = ReviewViewModel.factory(application, id),
                        )
                        ReviewScreen(reviewViewModel, timestamp, nav::popBackStack)
                    }
                }
            }
        }
    }
}

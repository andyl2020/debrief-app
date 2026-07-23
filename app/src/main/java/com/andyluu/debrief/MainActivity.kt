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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.andyluu.debrief.ui.RecorderScreen
import com.andyluu.debrief.ui.RecorderViewModel
import com.andyluu.debrief.ui.SettingsScreen
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val recorderViewModel: RecorderViewModel by viewModels()
    private val openRecorderRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as DebriefApplication).services.recorder.recoverInterruptedIfNeeded()
        val openRecorderOnLaunch = intent?.getBooleanExtra(EXTRA_OPEN_RECORDER, false) == true
        setContent {
            DebriefTheme {
                val nav = rememberNavController()
                val context = LocalContext.current
                val settings by appViewModel.settings.collectAsStateWithLifecycle()
                var folderPickStartsRecording by rememberSaveable {
                    mutableStateOf(false)
                }
                var pendingRecordingFolder by rememberSaveable {
                    mutableStateOf<String?>(null)
                }
                val recordingPermissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    val microphoneGranted = result[Manifest.permission.RECORD_AUDIO]
                        ?: (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED)
                    val folder = pendingRecordingFolder ?: settings.folderUri
                    if (microphoneGranted && folder != null) recorderViewModel.start(folder)
                    else if (!microphoneGranted) recorderViewModel.permissionDenied()
                    pendingRecordingFolder = null
                }
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri == null) {
                        folderPickStartsRecording = false
                        return@rememberLauncherForActivityResult
                    }
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                    val shouldStart = folderPickStartsRecording
                    folderPickStartsRecording = false
                    appViewModel.linkFolder(uri) { result ->
                        if (shouldStart && result.isSuccess) {
                            val folder = uri.toString()
                            pendingRecordingFolder = folder
                            val requested = buildList {
                                if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (requested.isEmpty()) {
                                recorderViewModel.start(folder)
                                pendingRecordingFolder = null
                            } else {
                                recordingPermissions.launch(requested.toTypedArray())
                            }
                        }
                    }
                }
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                LaunchedEffect(Unit) { appViewModel.scan() }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appViewModel.scan() }
                LaunchedEffect(nav) {
                    openRecorderRequests.collect {
                        nav.navigate("recorder") { launchSingleTop = true }
                    }
                }

                NavHost(
                    navController = nav,
                    startDestination = if (openRecorderOnLaunch) "recorder" else "library",
                ) {
                    composable("library") {
                        LibraryScreen(
                            viewModel = appViewModel,
                            onPickFolder = {
                                folderPickStartsRecording = false
                                folderPicker.launch(null)
                            },
                            onOpenRecording = { id, timestamp -> nav.navigate("review/$id?at=$timestamp") },
                            onOpenSearch = { nav.navigate("search") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenRecorder = { nav.navigate("recorder") { launchSingleTop = true } },
                            onRequestTranscription = { ids ->
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                appViewModel.transcribe(ids)
                            },
                        )
                    }
                    composable("recorder") {
                        RecorderScreen(
                            viewModel = recorderViewModel,
                            settings = settings,
                            onStart = {
                                val folder = settings.folderUri
                                if (folder == null) {
                                    folderPickStartsRecording = true
                                    folderPicker.launch(null)
                                } else {
                                    pendingRecordingFolder = folder
                                    val requested = buildList {
                                        if (ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO,
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    if (requested.isEmpty()) {
                                        recorderViewModel.start(folder)
                                        pendingRecordingFolder = null
                                    } else {
                                        recordingPermissions.launch(requested.toTypedArray())
                                    }
                                }
                            },
                            onPickFolder = {
                                folderPickStartsRecording = false
                                folderPicker.launch(null)
                            },
                            onOpenLibrary = {
                                if (!nav.popBackStack("library", false)) {
                                    nav.navigate("library") { launchSingleTop = true }
                                }
                            },
                            onOpenSettings = { nav.navigate("settings") },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_RECORDER, false)) {
            openRecorderRequests.tryEmit(Unit)
        }
    }

    companion object {
        const val EXTRA_OPEN_RECORDER = "open_recorder"
    }
}

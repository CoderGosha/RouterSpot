package app.routerspot

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.routerspot.ui.AboutScreen
import app.routerspot.ui.BottomNav
import app.routerspot.ui.DiagnosticsScreen
import app.routerspot.ui.HomeScreen
import app.routerspot.ui.LanguageScreen
import app.routerspot.ui.OnboardingScreen
import app.routerspot.ui.ProfilesScreen
import app.routerspot.ui.RouterSpotTheme
import app.routerspot.ui.SetupScreen
import app.routerspot.ui.SystemScreen
import app.routerspot.ui.colors

/** Страницы поверх вкладок. */
private enum class Overlay { NONE, LANGUAGE, GUIDE, SETUP, ABOUT, DIAGNOSTICS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent {
            RouterSpotTheme {
                val state by AppController.state.collectAsState()
                val profiles by AppController.profiles.profiles.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var overlay by rememberSaveable {
                    mutableStateOf(
                        when {
                            !AppController.languageChosen -> Overlay.LANGUAGE
                            !AppController.onboarded -> Overlay.GUIDE
                            // Без сопряжения начинаем с мастера настройки.
                            !state.paired -> Overlay.SETUP
                            else -> Overlay.NONE
                        },
                    )
                }
                // «Как это работает» из гайда возвращает обратно в гайд.
                var aboutFromGuide by rememberSaveable { mutableStateOf(false) }

                fun finishGuide(next: Overlay = if (state.paired) Overlay.NONE else Overlay.SETUP) {
                    AppController.onboarded = true
                    overlay = next
                }

                // При первом запуске выбор языка обязателен — «назад» с него не уводит.
                BackHandler(overlay != Overlay.NONE && (overlay != Overlay.LANGUAGE || AppController.languageChosen)) {
                    if (overlay == Overlay.GUIDE) AppController.onboarded = true
                    overlay = if (overlay == Overlay.ABOUT && aboutFromGuide) Overlay.GUIDE else Overlay.NONE
                }

                Column(Modifier.fillMaxSize().background(colors.bg).systemBarsPadding().imePadding()) {
                    when (overlay) {
                        Overlay.LANGUAGE -> LanguageScreen(
                            onBack = if (AppController.languageChosen) ({ overlay = Overlay.NONE }) else null,
                            onChosen = {
                                // Смена языка пересоздаёт Activity; состояние overlay сохранится.
                                overlay = if (AppController.onboarded) Overlay.NONE else Overlay.GUIDE
                                AppController.languageChosen = true
                            },
                        )
                        Overlay.GUIDE -> OnboardingScreen(
                            hasProfile = profiles.items.isNotEmpty(),
                            onOpenProfiles = { finishGuide(Overlay.NONE); tab = 1 },
                            onAbout = { aboutFromGuide = true; overlay = Overlay.ABOUT },
                            onDone = { finishGuide(); tab = 0 },
                        )
                        Overlay.SETUP -> SetupScreen(
                            state,
                            onPair = AppController::pair,
                            onLaunch = AppController::launchServer,
                            onClose = { overlay = Overlay.NONE; tab = 2 },
                            onDone = { overlay = Overlay.NONE; tab = 0 },
                        )
                        Overlay.DIAGNOSTICS -> DiagnosticsScreen(
                            onBack = { overlay = Overlay.NONE },
                            onOpenProfiles = { overlay = Overlay.NONE; tab = 1 },
                            onOpenSystem = { overlay = Overlay.SETUP },
                        )
                        Overlay.ABOUT -> AboutScreen(onBack = { overlay = if (aboutFromGuide) Overlay.GUIDE else Overlay.NONE })
                        Overlay.NONE -> {
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> HomeScreen(
                                        state, profiles,
                                        onStart = AppController::startSharing,
                                        onUsbMode = AppController::setUsbMode,
                                        onStop = AppController::stopSharing,
                                        onReconnect = AppController::reconnect,
                                        onOpenProfiles = { tab = 1 },
                                        onOpenSystem = { if (state.paired) tab = 2 else overlay = Overlay.SETUP },
                                    )
                                    1 -> ProfilesScreen(AppController.profiles, profiles)
                                    else -> SystemScreen(
                                        state,
                                        onSetup = { overlay = Overlay.SETUP },
                                        onAbout = { aboutFromGuide = false; overlay = Overlay.ABOUT },
                                        onGuide = { overlay = Overlay.GUIDE },
                                        onLanguage = { overlay = Overlay.LANGUAGE },
                                        onDiagnostics = { overlay = Overlay.DIAGNOSTICS },
                                    )
                                }
                            }
                            BottomNav(tab) { tab = it }
                        }
                    }
                }
            }
        }
    }
}

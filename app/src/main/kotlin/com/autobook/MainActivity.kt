package com.autobook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.autobook.data.db.AppDatabase
import com.autobook.data.repository.BookRepository
import com.autobook.ui.Screen
import com.autobook.ui.chapters.ChaptersScreen
import com.autobook.ui.chapters.ChaptersViewModel
import com.autobook.ui.import_.ImportScreen
import com.autobook.ui.import_.ImportViewModel
import com.autobook.ui.library.LibraryScreen
import com.autobook.ui.library.LibraryViewModel
import com.autobook.ui.player.PlayerScreen
import com.autobook.ui.player.PlayerViewModel
import com.autobook.ui.search.SearchScreen
import com.autobook.ui.settings.SettingsScreen
import com.autobook.ui.settings.SettingsViewModel
import com.autobook.ui.theme.AIAnyBookTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: BookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(applicationContext)
        repository = BookRepository(database.bookDao(), database.chapterDao())

        setContent {
            AIAnyBookTheme {
                AIAnyBookApp(repository = repository)
            }
        }
    }
}

@Composable
fun AIAnyBookApp(repository: BookRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route
    ) {
        composable(Screen.Library.route) {
            val viewModel: LibraryViewModel = viewModel(
                factory = LibraryViewModelFactory(repository, context)
            )
            LibraryScreen(
                viewModel = viewModel,
                onBookClick = { bookId ->
                    navController.navigate(Screen.Player.createRoute(bookId))
                },
                onImportClick = {
                    navController.navigate(Screen.Import.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                onSearchClick = {
                    navController.navigate(Screen.Search.route)
                }
            )
        }

        composable(Screen.Import.route) {
            val viewModel: ImportViewModel = viewModel(
                factory = ImportViewModelFactory(repository, navController.context)
            )
            ImportScreen(
                viewModel = viewModel,
                onImportComplete = {
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            val viewModel: PlayerViewModel = viewModel(
                factory = PlayerViewModelFactory(repository, navController.context)
            )

            PlayerScreen(
                viewModel = viewModel,
                bookId = bookId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Chapters.route,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
            val viewModel: ChaptersViewModel = viewModel(
                factory = ChaptersViewModelFactory(repository)
            )

            LaunchedEffect(bookId) {
                viewModel.loadChapters(bookId)
            }

            ChaptersScreen(
                viewModel = viewModel,
                bookId = bookId,
                onBackClick = { navController.popBackStack() },
                onChapterClick = { chapterIndex ->
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onBookImported = { bookId ->
                    navController.navigate(Screen.Player.createRoute(bookId)) {
                        popUpTo(Screen.Library.route)
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(navController.context.applicationContext)
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

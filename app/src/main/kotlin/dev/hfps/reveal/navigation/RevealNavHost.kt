package dev.hfps.reveal.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.hfps.reveal.ui.StartScreen

@Composable
fun RevealNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = StartRoute, modifier = modifier) {
        composable<StartRoute> { StartScreen() }
    }
}

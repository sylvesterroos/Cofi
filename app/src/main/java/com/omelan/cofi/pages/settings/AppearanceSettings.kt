@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.omelan.cofi.pages.settings

import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.omelan.cofi.R
import com.omelan.cofi.components.CofiSwitch
import com.omelan.cofi.components.PiPAwareAppBar
import com.omelan.cofi.components.createAppBarBehavior
import com.omelan.cofi.model.DYNAMIC_THEME
import com.omelan.cofi.model.DYNAMIC_THEME_DEFAULT_VALUE
import com.omelan.cofi.model.DataStore
import com.omelan.cofi.model.WAVY_TIMER
import com.omelan.cofi.model.WAVY_TIMER_DEFAULT_VALUE
import com.omelan.cofi.share.DataStore.Companion.dataStore
import com.omelan.cofi.utils.getDefaultPadding
import kotlinx.coroutines.launch

@Composable
fun AppearanceSettings(goBack: () -> Unit) {
    val context = LocalContext.current
    val dataStore = remember(context) { DataStore(context) }
    val coroutineScope = rememberCoroutineScope()
    val appBarBehavior = createAppBarBehavior()
    val isDynamicThemeEnabled by remember(dataStore) { dataStore.getDynamicThemeSetting() }
        .collectAsState(DYNAMIC_THEME_DEFAULT_VALUE)
    val isWavyTimerEnabled by remember(dataStore) { dataStore.getWavyTimerSetting() }
        .collectAsState(WAVY_TIMER_DEFAULT_VALUE)
    val toggleWavyTimer: () -> Unit = {
        coroutineScope.launch {
            context.dataStore.edit {
                it[WAVY_TIMER] = !(it[WAVY_TIMER] ?: WAVY_TIMER_DEFAULT_VALUE)
            }
        }
    }
    val toggleDynamicTheme: () -> Unit = {
        coroutineScope.launch {
            context.dataStore.edit {
                it[DYNAMIC_THEME] = !(it[DYNAMIC_THEME] ?: DYNAMIC_THEME_DEFAULT_VALUE)
            }
        }
    }
    Scaffold(
        topBar = {
            PiPAwareAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.settings_appearance_item),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(id = R.string.button_back),
                        )
                    }
                },
                scrollBehavior = appBarBehavior,
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .nestedScroll(appBarBehavior.nestedScrollConnection)
                .fillMaxSize(),
            contentPadding = getDefaultPadding(
                paddingValues = it,
                additionalStartPadding = 0.dp,
                additionalEndPadding = 0.dp,
            ),
        ) {
            item {
                ListItem(
                    content = {
                        Text(text = stringResource(id = R.string.settings_dynamic_theme_item))
                    },
                    leadingContent = {
                        Icon(
                            Icons.Rounded.Face,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .settingsItemModifier(
                            onClick = toggleDynamicTheme,
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        ),
                    trailingContent = {
                        CofiSwitch(
                            checked = isDynamicThemeEnabled,
                            onCheckedChange = { toggleDynamicTheme() },
                            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        )
                    },
                )
            }
            item {
                ListItem(
                    content = {
                        Text(text = stringResource(id = R.string.settings_wavy_timer_item))
                    },
                    leadingContent = {
                        Icon(
                            painterResource(id = R.drawable.ic_timer),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier
                        .settingsItemModifier(
                            onClick = toggleWavyTimer,
                        ),
                    trailingContent = {
                        CofiSwitch(
                            checked = isWavyTimerEnabled,
                            onCheckedChange = { toggleWavyTimer() },
                        )
                    },
                )
            }
        }
    }
}

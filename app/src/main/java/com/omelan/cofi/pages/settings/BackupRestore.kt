@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.omelan.cofi.pages.settings

import android.database.sqlite.SQLiteException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.omelan.cofi.R
import com.omelan.cofi.components.Material3Dialog
import com.omelan.cofi.components.PiPAwareAppBar
import com.omelan.cofi.components.createAppBarBehavior
import com.omelan.cofi.share.model.*
import com.omelan.cofi.utils.getDefaultPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreSettings(goBack: () -> Unit, goToRecipeList: () -> Unit) {
    val context = LocalContext.current
    val snackbarState = remember { SnackbarHostState() }
    var showDefaultRecipeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val appBarBehavior = createAppBarBehavior()
    Scaffold(
        topBar = {
            PiPAwareAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.settings_backup_item),
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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarState,
                modifier = Modifier.padding(getDefaultPadding()),
            ) {
                Snackbar(
                    action = {
                        it.visuals.actionLabel?.let { label ->
                            TextButton(onClick = goToRecipeList) {
                                Text(text = label)
                            }
                        }
                    },
                ) {
                    Text(text = it.visuals.message)
                }
            }
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
                        Text(text = stringResource(id = R.string.settings_addDefault))
                    },
                    leadingContent = { Icon(Icons.Rounded.AddCircle, contentDescription = null) },
                    modifier = Modifier.settingsItemModifier(
                        onClick = { showDefaultRecipeDialog = true },
                    ),
                )
                if (showDefaultRecipeDialog) {
                    DefaultRecipesDialog(dismiss = { showDefaultRecipeDialog = false })
                }
            }
            item {
                ListItem(
                    content = {
                        Text(text = stringResource(id = R.string.settings_backup))
                    },
                    leadingContent = {
                        Icon(
                            painterResource(id = R.drawable.ic_save),
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.settingsItemModifier(
                        onClick = { showBackupDialog = true },
                    ),
                )
                if (showBackupDialog) {
                    BackupDialog(
                        dismiss = { showBackupDialog = false },
                        afterBackup = { numberOfBackedUp ->
                            coroutineScope.launch {
                                snackbarState.showSnackbar(
                                    context.resources.getQuantityString(
                                        R.plurals.settings_snackbar_backup,
                                        numberOfBackedUp,
                                        numberOfBackedUp,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
            item {
                RestoreListItem(
                    afterRestore = { numberOfRestored ->
                        coroutineScope.launch {
                            snackbarState.showSnackbar(
                                context.resources.getQuantityString(
                                    R.plurals.settings_snackbar_restore,
                                    numberOfRestored,
                                    numberOfRestored,
                                ),
                                actionLabel = context.resources.getString(R.string.button_show),
                            )
                        }
                    },
                    onRestoreError = {
                        coroutineScope.launch {
                            snackbarState.showSnackbar(
                                context.resources.getString(R.string.settings_restore_error),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun RestoreListItem(afterRestore: (numberOfRestored: Int) -> Unit, onRestoreError: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val coroutineScope = rememberCoroutineScope()

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch {
                val numberOfRestored = try {
                    withContext(Dispatchers.IO) {
                        val jsonString = context.contentResolver.openInputStream(uri)?.use {
                            String(it.readBytes(), StandardCharsets.UTF_8)
                        } ?: throw IOException("Cannot open $uri")
                        val jsonArray = JSONArray(jsonString)
                        db.withTransaction {
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)
                                val recipeId = db.recipeDao().insertRecipe(jsonObject.toRecipe())
                                db.stepDao().insertAll(
                                    jsonObject.getJSONArray(jsonSteps).toSteps(recipeId = recipeId),
                                )
                            }
                        }
                        jsonArray.length()
                    }
                } catch (_: JSONException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: SQLiteException) {
                    null
                }
                if (numberOfRestored == null) {
                    onRestoreError()
                } else {
                    afterRestore(numberOfRestored)
                }
            }
        }

    ListItem(
        content = { Text(text = stringResource(id = R.string.settings_restore)) },
        leadingContent = {
            Icon(
                painterResource(id = R.drawable.ic_restore),
                contentDescription = null,
            )
        },
        modifier = Modifier.settingsItemModifier(
            onClick = { launcher.launch(arrayOf("application/json")) },
        ),
    )
}

@Composable
fun BackupDialog(dismiss: () -> Unit, afterBackup: (numberOfBackups: Int) -> Unit) {
    val recipesToBackup = remember { mutableStateListOf<Recipe>() }
    val context = LocalContext.current
    val db = AppDatabase.getInstance(context)
    val recipes by db.recipeDao().getAll().observeAsState(initial = emptyList())
    LaunchedEffect(recipes.isEmpty()) {
        if (recipesToBackup.isEmpty()) {
            recipesToBackup.addAll(recipes)
        }
    }
    val steps by db.stepDao().getAll().observeAsState()
    val stepsWithRecipeId = steps.orEmpty().groupBy { it.recipeId }
    val launcher = rememberLauncherForActivityResult(CreateDocument("application/json")) {
        if (it == null) {
            return@rememberLauncherForActivityResult
        }
        val contentResolver = context.contentResolver
        contentResolver.openOutputStream(it).use { outputStream ->
            if (outputStream == null) return@rememberLauncherForActivityResult
            val jsonArray = JSONArray()
            recipesToBackup.forEach { recipe ->
                jsonArray.put(recipe.serialize(stepsWithRecipeId[recipe.id]))
            }
            outputStream.write(jsonArray.toString(2).toByteArray())
            outputStream.close()
        }
        afterBackup(recipesToBackup.size)
        dismiss()
    }
    val isNothingToBackUp = steps?.isEmpty() == true
    LaunchedEffect(isNothingToBackUp) {
        if (isNothingToBackUp) dismiss()
    }
    if (steps.isNullOrEmpty()) {
        return
    }

    Material3Dialog(
        onDismissRequest = dismiss,
        onSave = {
            val formattedDate = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            launcher.launch("cofi_backup_$formattedDate.json")
        },
        title = { Text(text = stringResource(id = R.string.settings_backup)) },
    ) {
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(recipes) {
                val isSelected = recipesToBackup.contains(it)
                val onCheck: () -> Unit = {
                    if (isSelected) recipesToBackup.remove(it) else recipesToBackup.add(it)
                }
                ListItem(
                    content = { Text(it.name) },
                    modifier = Modifier
                        .selectable(selected = isSelected, onClick = onCheck),
                    leadingContent = {
                        Checkbox(checked = isSelected, onCheckedChange = { onCheck() })
                    },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
@ExperimentalMaterial3ExpressiveApi
fun DefaultRecipesDialog(dismiss: () -> Unit) {
    val recipesToAdd = remember { mutableStateListOf<Recipe>() }
    var isBusy by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prepopulateData = PrepopulateData(context)
    val steps = prepopulateData.steps.groupBy { it.recipeId }
    val db = AppDatabase.getInstance(context)

    Material3Dialog(
        onDismissRequest = dismiss,
        isBusy = isBusy,
        onSave = {
            isBusy = true
            val jobs = recipesToAdd.map { recipeToPrepopulate ->
                val job = coroutineScope.launch {
                    val newRecipeEntity = recipeToPrepopulate.copy(id = 0)
                    val newRecipeId = db.recipeDao().insertRecipe(newRecipeEntity)
                    val stepsOfOriginalRecipe = steps[recipeToPrepopulate.id]
                    stepsOfOriginalRecipe?.let { originalSteps ->
                        val newStepsEntities = originalSteps.map { originalStep ->
                            originalStep.copy(
                                id = 0,
                                recipeId = newRecipeId.toInt(),
                            )
                        }
                        db.stepDao().insertAll(newStepsEntities)
                    }
                }
                return@map job
            }
            coroutineScope.launch {
                jobs.joinAll()
                isBusy = false
                dismiss()
            }
        },
        title = { Text(text = stringResource(id = R.string.settings_addDefault)) },
    ) {
        HorizontalDivider()
        Box(contentAlignment = Alignment.Center) {
            LazyColumn {
                items(prepopulateData.recipes) {
                    val isSelected = recipesToAdd.contains(it)
                    val onCheck: () -> Unit = {
                        if (isSelected) recipesToAdd.remove(it) else recipesToAdd.add(it)
                    }
                    ListItem(
                        content = { Text(it.name) },
                        modifier = Modifier.selectable(
                            selected = isSelected,
                            onClick = onCheck,
                            enabled = !isBusy,
                        ),
                        leadingContent = {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onCheck() },
                                enabled = !isBusy,
                            )
                        },
                    )
                }
            }
            if (isBusy) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            }
        }
        HorizontalDivider()
    }
}

@ExperimentalMaterial3Api
@Preview
@Composable
fun BackupRestoreSettingsPreview() {
    BackupRestoreSettings(goBack = {}, goToRecipeList = {})
}

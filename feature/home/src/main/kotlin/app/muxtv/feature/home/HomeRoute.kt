package app.muxtv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvActionStyle
import app.muxtv.designsystem.component.MuxTvChannelLogo
import app.muxtv.designsystem.component.MuxTvEmptyState
import app.muxtv.designsystem.component.MuxTvFocusSurface
import app.muxtv.designsystem.component.MuxTvProgrammeProgress
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import app.muxtv.designsystem.component.MuxTvSectionHeader
import app.muxtv.designsystem.icon.MuxTvIcons
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@Composable
fun HomeRoute(
    channelBrowseRepository: ChannelBrowseRepository,
    recentChannelsRepository: RecentChannelsRepository,
    epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    hasSources: Flow<Boolean>,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    onOpenChannels: () -> Unit,
    onOpenGuide: () -> Unit,
    onAddSource: () -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val factory = remember(
        channelBrowseRepository,
        recentChannelsRepository,
        epgGuideRepository,
        playbackSessionStateSource,
        hasSources,
        profileId,
    ) {
        viewModelFactory {
            initializer {
                HomeViewModel(
                    recentChannelsRepository = recentChannelsRepository,
                    epgGuideRepository = epgGuideRepository,
                    playbackSessionStateSource = playbackSessionStateSource,
                    hasSources = hasSources,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: HomeViewModel = viewModel(factory = factory)
    val sourceState by screenViewModel.sourceState.collectAsStateWithLifecycle()
    val sessionState by screenViewModel.playbackSessionState.collectAsStateWithLifecycle()
    val recent by screenViewModel.recent.collectAsStateWithLifecycle()
    val nowNext by screenViewModel.nowNext.collectAsStateWithLifecycle()
    val favoritesFlow = remember(channelBrowseRepository, profileId) {
        channelBrowseRepository.pages(
            query = ChannelBrowseQuery(
                profileId = profileId,
                filter = ChannelBrowseFilter.FAVORITES,
            ),
        )
    }
    val favorites = favoritesFlow.collectAsLazyPagingItems()
    val favoriteItems = remember(favorites.itemSnapshotList) {
        favorites.itemSnapshotList.filterNotNull()
    }

    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_REFRESH_MILLIS)
            nowEpochMillis = System.currentTimeMillis()
        }
    }

    val hero = buildHomeHero(sessionState, recent, nowNext, nowEpochMillis)
    val recentCards = buildRecentRail(recent, nowNext, sessionState, nowEpochMillis)
    val favoriteCards = buildFavoritesRail(favoriteItems, nowNext, nowEpochMillis)

    val nowNextIds = remember(hero?.channelId, favoriteCards, recentCards) {
        buildList {
            hero?.channelId?.let(::add)
            addAll(favoriteCards.map(HomeChannelCard::channelId))
            addAll(recentCards.map(HomeChannelCard::channelId))
        }
    }
    LaunchedEffect(nowNextIds) {
        screenViewModel.setNowNextIds(nowNextIds)
    }

    val heroFocusRequester = remember { FocusRequester() }
    val heroDownRequester = remember { FocusRequester() }
    var activeFocusOwner by rememberSaveable(profileId) {
        mutableStateOf(HOME_FOCUS_OWNER_HERO)
    }

    // Home already has a persistent selected destination in the rail. Omitting a
    // second large route title restores the reference hierarchy: clock -> hero -> rails.
    MuxTvScreenScaffold(
        title = null,
        modifier = modifier,
    ) {
        when (sourceState) {
            HomeSourceState.Loading -> HomeLoadingState(
                onOpenChannels = onOpenChannels,
                railFocusRequester = railFocusRequester,
            )

            HomeSourceState.Failed -> HomeSourceFailureState(
                onOpenChannels = onOpenChannels,
                railFocusRequester = railFocusRequester,
            )

            HomeSourceState.Empty -> HomeEmptyState(
                onAddSource = onAddSource,
                railFocusRequester = railFocusRequester,
            )

            HomeSourceState.Present -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val contentMaxHeight = maxHeight
                Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.sectionGap)) {
                    HomeHero(
                        hero = hero,
                        onOpenChannel = onOpenChannel,
                        onOpenChannels = onOpenChannels,
                        onOpenGuide = onOpenGuide,
                        heroFocusRequester = heroFocusRequester,
                        railFocusRequester = railFocusRequester,
                        downFocusRequester = heroDownRequester,
                        requestInitialFocus = activeFocusOwner == HOME_FOCUS_OWNER_HERO,
                        onFocused = { activeFocusOwner = HOME_FOCUS_OWNER_HERO },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(contentMaxHeight * HERO_HEIGHT_FRACTION),
                    )
                    HomeRails(
                        favoriteCards = favoriteCards,
                        recentCards = recentCards,
                        onOpenChannel = onOpenChannel,
                        railFocusRequester = railFocusRequester,
                        heroFocusRequester = heroFocusRequester,
                        firstCardFocusRequester = heroDownRequester,
                        activeFocusOwner = activeFocusOwner,
                        onFocusOwnerChanged = { activeFocusOwner = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLoadingState(
    onOpenChannels: () -> Unit,
    railFocusRequester: FocusRequester?,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    MuxTvActionButton(
        text = "Открыть эфир",
        onClick = onOpenChannels,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
    )
}

@Composable
private fun HomeSourceFailureState(
    onOpenChannels: () -> Unit,
    railFocusRequester: FocusRequester?,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        requester.requestFocus()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        MuxTvEmptyState(
            icon = MuxTvIcons.Info,
            title = "Не удалось прочитать состояние источников",
            description = "MuxTV не будет считать это пустой библиотекой. Можно открыть эфир или перейти в навигацию.",
            actionLabel = "Открыть эфир",
            actionTestTag = HOME_SOURCE_FAILURE_ACTION_TEST_TAG,
            onAction = onOpenChannels,
            actionModifier = Modifier
                .focusRequester(requester)
                .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HomeEmptyState(
    onAddSource: () -> Unit,
    railFocusRequester: FocusRequester?,
) {
    val actionFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        actionFocusRequester.requestFocus()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        MuxTvEmptyState(
            icon = MuxTvIcons.Info,
            title = "Добро пожаловать в MuxTV",
            description = "Добавьте источник M3U, и MuxTV соберёт единый список каналов.",
            actionLabel = "Добавить источник",
            actionTestTag = HOME_ADD_SOURCE_TEST_TAG,
            onAction = onAddSource,
            actionModifier = Modifier
                .focusRequester(actionFocusRequester)
                .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HomeHero(
    hero: HomeHeroModel,
    onOpenChannel: (String) -> Unit,
    onOpenChannels: () -> Unit,
    onOpenGuide: () -> Unit,
    heroFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    requestInitialFocus: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            withFrameNanos { }
            heroFocusRequester.requestFocus()
        }
    }
    val heroBrush = Brush.horizontalGradient(
        colors = listOf(
            TvTokens.Color.surfaceRaised,
            TvTokens.Color.accentSoft2,
            TvTokens.Color.accentSoft,
        ),
    )
    MuxTvFocusSurface(
        onClick = {
            hero.channelId?.let(onOpenChannel) ?: onOpenChannels()
        },
        corner = TvTokens.Shape.heroCorner,
        contentPadding = TvTokens.Spacing.large,
        containerBrush = heroBrush,
        modifier = modifier
            .testTag(HOME_HERO_TEST_TAG)
            .focusRequester(heroFocusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusProperties {
                left = railFocusRequester ?: FocusRequester.Default
                downFocusRequester?.let { down = it }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(HERO_TEXT_WIDTH_FRACTION)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            if (hero.hasChannel) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MuxTvChannelLogo(
                        name = hero.displayName.orEmpty(),
                        size = 56.dp,
                        corner = TvTokens.Shape.logoCorner,
                    )
                    Spacer(Modifier.width(TvTokens.Spacing.medium))
                    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.micro)) {
                        Text(
                            text = buildString {
                                hero.channelNumber?.takeIf(String::isNotBlank)?.let {
                                    append(it).append(" · ")
                                }
                                append(hero.displayName.orEmpty())
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hero.isCurrentPlayback) {
                                Icon(
                                    imageVector = MuxTvIcons.Playing,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                                Spacer(Modifier.width(TvTokens.Spacing.xSmall))
                            }
                            if (hero.isFavorite) {
                                Icon(
                                    imageVector = MuxTvIcons.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = TvTokens.Color.accent,
                                )
                                Spacer(Modifier.width(TvTokens.Spacing.xSmall))
                            }
                            Text(
                                text = if (hero.isCurrentPlayback) "Сейчас в эфире" else "Последний просмотр",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (hero.isCurrentPlayback) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(TvTokens.Spacing.medium))
                Text(
                    text = hero.currentTitle ?: hero.displayName.orEmpty(),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = TvTokens.Typography.heroTitle,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hero.progressFraction != null) {
                    Spacer(Modifier.height(TvTokens.Spacing.small))
                    MuxTvProgrammeProgress(fraction = hero.progressFraction, height = 6.dp)
                }
                if (hero.nextTitle != null) {
                    Spacer(Modifier.height(TvTokens.Spacing.small))
                    Text(
                        text = "Далее · ${hero.nextTitle}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Прямой эфир",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = TvTokens.Typography.heroTitle,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(TvTokens.Spacing.small))
                Text(
                    text = "Каналы из ваших источников — в едином списке.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(TvTokens.Spacing.medium))
            Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                MuxTvActionButton(
                    text = hero.primaryActionLabel,
                    onClick = {
                        hero.channelId?.let(onOpenChannel) ?: onOpenChannels()
                    },
                    style = MuxTvActionStyle.Primary,
                    modifier = Modifier.testTag(HOME_HERO_PRIMARY_TEST_TAG),
                )
                MuxTvActionButton(
                    text = "Программа",
                    onClick = onOpenGuide,
                    modifier = Modifier.testTag(HOME_HERO_GUIDE_TEST_TAG),
                )
            }
        }
    }
}

@Composable
private fun HomeRails(
    favoriteCards: List<HomeChannelCard>,
    recentCards: List<HomeChannelCard>,
    onOpenChannel: (String) -> Unit,
    railFocusRequester: FocusRequester?,
    heroFocusRequester: FocusRequester,
    firstCardFocusRequester: FocusRequester,
    activeFocusOwner: String,
    onFocusOwnerChanged: (String) -> Unit,
) {
    var firstRailRendered = false
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.large)) {
        if (favoriteCards.isNotEmpty()) {
            MuxTvSectionHeader(
                title = "Избранное",
                modifier = Modifier.testTag(HOME_FAVORITES_HEADER_TEST_TAG),
            )
            HomeChannelRail(
                railKey = HOME_FOCUS_OWNER_FAVORITES,
                cards = favoriteCards,
                onOpenChannel = onOpenChannel,
                railFocusRequester = railFocusRequester,
                upFocusRequester = heroFocusRequester,
                firstCardOverrideRequester = if (!firstRailRendered) firstCardFocusRequester else null,
                restoreFocus = activeFocusOwner == HOME_FOCUS_OWNER_FAVORITES,
                onFocused = { onFocusOwnerChanged(HOME_FOCUS_OWNER_FAVORITES) },
            )
            firstRailRendered = true
        }
        if (recentCards.isNotEmpty()) {
            MuxTvSectionHeader(
                title = "Недавние",
                modifier = Modifier.testTag(HOME_RECENT_HEADER_TEST_TAG),
            )
            HomeChannelRail(
                railKey = HOME_FOCUS_OWNER_RECENT,
                cards = recentCards,
                onOpenChannel = onOpenChannel,
                railFocusRequester = railFocusRequester,
                upFocusRequester = heroFocusRequester,
                firstCardOverrideRequester = if (!firstRailRendered) firstCardFocusRequester else null,
                restoreFocus = activeFocusOwner == HOME_FOCUS_OWNER_RECENT,
                onFocused = { onFocusOwnerChanged(HOME_FOCUS_OWNER_RECENT) },
            )
        }
    }
}

@Composable
private fun HomeChannelRail(
    railKey: String,
    cards: List<HomeChannelCard>,
    onOpenChannel: (String) -> Unit,
    railFocusRequester: FocusRequester?,
    upFocusRequester: FocusRequester,
    firstCardOverrideRequester: FocusRequester?,
    restoreFocus: Boolean,
    onFocused: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var anchorChannelId by rememberSaveable(railKey) { mutableStateOf<String?>(null) }
    var anchorScrollOffset by rememberSaveable(railKey) { mutableStateOf(0) }
    var restorationCompleted by remember(railKey, cards.map(HomeChannelCard::channelId)) {
        mutableStateOf(false)
    }

    LaunchedEffect(
        anchorChannelId,
        cards.map(HomeChannelCard::channelId),
        restorationCompleted,
        restoreFocus,
    ) {
        if (!restoreFocus || restorationCompleted || anchorChannelId == null || cards.isEmpty()) {
            return@LaunchedEffect
        }
        val targetIndex = cards.indexOfFirst { it.channelId == anchorChannelId }
            .takeIf { it >= 0 }
            ?: 0
        listState.scrollToItem(targetIndex, anchorScrollOffset)
        withFrameNanos { }
        focusRequesters[cards[targetIndex].channelId]?.requestFocus()
        restorationCompleted = true
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        items(count = cards.size, key = { index -> "${railKey}-${cards[index].channelId}" }) { index ->
            val card = cards[index]
            val ownRequester = remember(card.channelId) { FocusRequester() }
            val focusRequester = if (index == 0 && firstCardOverrideRequester != null) {
                firstCardOverrideRequester
            } else {
                ownRequester
            }
            DisposableEffect(card.channelId, focusRequester) {
                focusRequesters[card.channelId] = focusRequester
                onDispose {
                    if (focusRequesters[card.channelId] === focusRequester) {
                        focusRequesters.remove(card.channelId)
                    }
                }
            }
            fun captureAnchor() {
                anchorChannelId = card.channelId
                anchorScrollOffset = listState.firstVisibleItemScrollOffset
                onFocused()
            }
            HomeChannelCardView(
                card = card,
                onClick = {
                    captureAnchor()
                    onOpenChannel(card.channelId)
                },
                modifier = Modifier
                    .testTag("home-card-$railKey-$index")
                    .focusRequester(focusRequester)
                    .focusProperties {
                        up = upFocusRequester
                        if (index == 0) {
                            left = railFocusRequester ?: FocusRequester.Default
                        }
                    }
                    .onFocusChanged { focusState -> if (focusState.isFocused) captureAnchor() },
            )
        }
    }
}

@Composable
private fun HomeChannelCardView(
    card: HomeChannelCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuxTvFocusSurface(
        onClick = onClick,
        corner = TvTokens.Shape.largeCardCorner,
        contentPadding = 16.dp,
        focusScale = TvTokens.Focus.cardScale,
        modifier = modifier.size(TvTokens.Size.homeCardWidth, TvTokens.Size.homeCardHeight),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MuxTvChannelLogo(name = card.displayName, size = 44.dp)
                Spacer(Modifier.width(TvTokens.Spacing.small))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = card.displayName,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = TvTokens.Typography.cardTitle,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (card.isPlaying) {
                            Icon(
                                imageVector = MuxTvIcons.Playing,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.width(TvTokens.Spacing.micro))
                        }
                        if (card.isFavorite) {
                            Icon(
                                imageVector = MuxTvIcons.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = TvTokens.Color.accent,
                            )
                            Spacer(Modifier.width(TvTokens.Spacing.micro))
                        }
                        Text(
                            text = card.currentTitle ?: " ",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = TvTokens.Typography.metadata,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (card.progressFraction != null) {
                MuxTvProgrammeProgress(fraction = card.progressFraction, height = 4.dp)
            }
        }
    }
}

private const val HERO_HEIGHT_FRACTION = 0.46f
private const val HERO_TEXT_WIDTH_FRACTION = 0.68f
private const val NOW_REFRESH_MILLIS = 60_000L
private const val HOME_FOCUS_OWNER_HERO = "hero"
private const val HOME_FOCUS_OWNER_FAVORITES = "favorites"
private const val HOME_FOCUS_OWNER_RECENT = "recent"
const val HOME_HERO_TEST_TAG = "home-hero"
const val HOME_HERO_PRIMARY_TEST_TAG = "home-hero-primary"
const val HOME_HERO_GUIDE_TEST_TAG = "home-hero-guide"
const val HOME_ADD_SOURCE_TEST_TAG = "home-add-source"
const val HOME_SOURCE_FAILURE_ACTION_TEST_TAG = "home-source-failure-action"
const val HOME_FAVORITES_HEADER_TEST_TAG = "home-favorites-header"
const val HOME_RECENT_HEADER_TEST_TAG = "home-recent-header"

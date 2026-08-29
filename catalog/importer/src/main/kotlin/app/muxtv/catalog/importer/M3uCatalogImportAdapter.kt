package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.M3uEntry

internal fun M3uEntry.toCatalogImportEntry(): CatalogImportEntry = CatalogImportEntry(
    providerStableId = null,
    displayName = displayName,
    playbackReference = locator,
    tvgId = tvgId,
    tvgName = tvgName,
    logoUrl = tvgLogo,
    groupTitle = groupTitle,
    channelNumber = channelNumber,
    catchupMode = catchupMode,
    catchupSource = catchupSource,
    catchupDays = catchupDays,
    catchupCorrection = catchupCorrection,
    userAgent = userAgent,
    referrer = referrer,
)

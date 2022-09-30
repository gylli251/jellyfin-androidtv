package org.jellyfin.androidtv.ui.itemhandling;

import android.app.Activity;
import android.content.Intent;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.constant.LiveTvOption;
import org.jellyfin.androidtv.data.model.ChapterItemInfo;
import org.jellyfin.androidtv.preference.LibraryPreferences;
import org.jellyfin.androidtv.preference.PreferencesRepository;
import org.jellyfin.androidtv.ui.navigation.Destinations;
import org.jellyfin.androidtv.ui.navigation.NavigationRepository;
import org.jellyfin.androidtv.ui.playback.MediaManager;
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.PlaybackHelper;
import org.jellyfin.androidtv.util.sdk.compat.JavaCompat;
import org.jellyfin.androidtv.util.sdk.compat.ModelCompat;
import org.jellyfin.apiclient.interaction.ApiClient;
import org.jellyfin.apiclient.interaction.Response;
import org.jellyfin.apiclient.model.dto.BaseItemDto;
import org.jellyfin.apiclient.model.dto.BaseItemType;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.PlayAccess;
import org.jellyfin.sdk.model.api.SearchHint;
import org.jellyfin.sdk.model.constant.CollectionType;
import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import timber.log.Timber;

public class ItemLauncher {
    public static void launchUserView(final org.jellyfin.sdk.model.api.BaseItemDto baseItem) {
        NavigationRepository navigationRepository = KoinJavaComponent.<NavigationRepository>get(NavigationRepository.class);
        Timber.d("**** Collection type: %s", baseItem.getCollectionType());

        switch (baseItem.getCollectionType()) {
            case CollectionType.Movies:
            case CollectionType.TvShows:
                LibraryPreferences displayPreferences = KoinJavaComponent.<PreferencesRepository>get(PreferencesRepository.class).getLibraryPreferences(baseItem.getDisplayPreferencesId());
                boolean enableSmartScreen = displayPreferences.get(LibraryPreferences.Companion.getEnableSmartScreen());
                if (!enableSmartScreen) {
                    navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(baseItem));
                } else {
                    navigationRepository.navigate(Destinations.INSTANCE.librarySmartScreen(baseItem));
                }
                break;
            case CollectionType.Music:
            case CollectionType.LiveTv:
                navigationRepository.navigate(Destinations.INSTANCE.librarySmartScreen(baseItem));
                break;
            default:
                navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(baseItem));
        }
    }

    public static void launch(final BaseRowItem rowItem, ItemRowAdapter adapter, int pos, final Activity activity) {
        NavigationRepository navigationRepository = KoinJavaComponent.<NavigationRepository>get(NavigationRepository.class);
        KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentMediaAdapter(adapter);

        switch (rowItem.getBaseRowType()) {
            case BaseItem:
                org.jellyfin.sdk.model.api.BaseItemDto baseItem = rowItem.getBaseItem();
                try {
                    Timber.d("Item selected: %d - %s (%s)", rowItem.getIndex(), baseItem.getName(), baseItem.getType().toString());
                } catch (Exception e) {
                    //swallow it
                }

                //specialized type handling
                switch (baseItem.getType()) {
                    case USER_VIEW:
                    case COLLECTION_FOLDER:
                        launchUserView(baseItem);
                        return;
                    case SERIES:
                    case MUSIC_ARTIST:
                        navigationRepository.navigate(Destinations.INSTANCE.itemDetails(baseItem.getId()));
                        return;

                    case MUSIC_ALBUM:
                    case PLAYLIST:
                        navigationRepository.navigate(Destinations.INSTANCE.itemList(baseItem.getId()));
                        return;

                    case AUDIO:
                        Timber.d("got pos %s", pos);
                        if (rowItem.getBaseItem() == null)
                            return;

                        PlaybackLauncher playbackLauncher = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class);
                        if (playbackLauncher.interceptPlayRequest(activity, rowItem.getBaseItem()))
                            return;

                        MediaManager mediaManager = KoinJavaComponent.<MediaManager>get(MediaManager.class);

                        // if the song currently playing is selected (and is the exact item - this only happens in the nowPlayingRow), open AudioNowPlayingActivity
                        if (mediaManager.hasAudioQueueItems() && rowItem instanceof AudioQueueItem && rowItem.getBaseItem().getId().equals(mediaManager.getCurrentAudioItem().getId())) {
                            navigationRepository.navigate(Destinations.INSTANCE.getNowPlaying());
                        } else if (mediaManager.hasAudioQueueItems() && rowItem instanceof AudioQueueItem && pos < mediaManager.getCurrentAudioQueueSize()) {
                            Timber.d("playing audio queue item");
                            mediaManager.playFrom(pos);
                        } else {
                            Timber.d("playing audio item");
                            List<org.jellyfin.sdk.model.api.BaseItemDto> audioItemsAsList = new ArrayList<>();

                            for (Object item : adapter) {
                                if (item instanceof BaseRowItem && ((BaseRowItem) item).getBaseItem() != null)
                                    audioItemsAsList.add(((BaseRowItem) item).getBaseItem());
                            }
                            mediaManager.playNow(activity, audioItemsAsList, pos, false);
                        }

                        return;
                    case SEASON:
                        navigationRepository.navigate(Destinations.INSTANCE.folderBrowser(baseItem));
                        return;

                    case BOX_SET:
                        navigationRepository.navigate(Destinations.INSTANCE.collectionBrowser(baseItem));
                        return;

                    case PHOTO:
                        navigationRepository.navigate(Destinations.INSTANCE.getPicturePlayer());
                        return;

                }

                // or generic handling
                if (baseItem.isFolder()) {
                    // Some items don't have a display preferences id, but it's required for StdGridFragment
                    // Use the id of the item as a workaround, it's a unique key for the specific item
                    // Which is exactly what we want
                    if (baseItem.getDisplayPreferencesId() == null) {
                        baseItem = JavaCompat.copyWithDisplayPreferencesId(baseItem, baseItem.getId().toString());
                    }

                    navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(baseItem));
                } else {
                    switch (rowItem.getSelectAction()) {

                        case ShowDetails:
                            navigationRepository.navigate(Destinations.INSTANCE.itemDetails(baseItem.getId()));
                            break;
                        case Play:
                            if (baseItem.getPlayAccess() == org.jellyfin.sdk.model.api.PlayAccess.FULL) {
                                //Just play it directly
                                final BaseItemKind itemType = baseItem.getType();
                                PlaybackHelper.getItemsToPlay(baseItem, baseItem.getType() == BaseItemKind.MOVIE, false, new Response<List<org.jellyfin.sdk.model.api.BaseItemDto>>() {
                                    @Override
                                    public void onResponse(List<org.jellyfin.sdk.model.api.BaseItemDto> response) {
                                        Class newActivity = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class).getPlaybackActivityClass(itemType);
                                        Intent intent = new Intent(activity, newActivity);
                                        KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentVideoQueue(response);
                                        intent.putExtra("Position", 0);
                                        activity.startActivity(intent);
                                    }
                                });
                            } else {
                                Utils.showToast(activity, "Item not playable at this time");
                            }
                            break;
                    }
                }
                break;
            case Person:
                navigationRepository.navigate(Destinations.INSTANCE.itemDetails(rowItem.getBasePerson().getId()));

                break;
            case Chapter:
                final ChapterItemInfo chapter = rowItem.getChapterInfo();
                //Start playback of the item at the chapter point
                KoinJavaComponent.<ApiClient>get(ApiClient.class).GetItemAsync(chapter.getItemId().toString(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                    @Override
                    public void onResponse(BaseItemDto response) {
                        List<BaseItemDto> items = new ArrayList<>();
                        items.add(response);
                        KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentVideoQueue(JavaCompat.mapBaseItemCollection(items));
                        Class newActivity = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class).getPlaybackActivityClass(ModelCompat.asSdk(response).getType());
                        Intent intent = new Intent(activity, newActivity);
                        Long start = chapter.getStartPositionTicks() / 10000;
                        intent.putExtra("Position", start.intValue());
                        activity.startActivity(intent);
                    }
                });

                break;

            case SearchHint:
                final SearchHint hint = rowItem.getSearchHint();
                //Retrieve full item for display and playback
                KoinJavaComponent.<ApiClient>get(ApiClient.class).GetItemAsync(hint.getItemId().toString(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                    @Override
                    public void onResponse(BaseItemDto response) {
                        if (response.getIsFolderItem() && ModelCompat.asSdk(response).getType() != BaseItemKind.SERIES) {
                            navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(response)));
                        } else if (ModelCompat.asSdk(response).getType() == BaseItemKind.AUDIO) {
                            PlaybackHelper.retrieveAndPlay(response.getId(), false, activity);
                            //produce item menu
//                            KeyProcessor.HandleKey(KeyEvent.KEYCODE_MENU, rowItem, (BaseActivity) activity);
                            return;

                        } else {
                            if (ModelCompat.asSdk(response).getType() == BaseItemKind.PROGRAM) {
                                navigationRepository.navigate(Destinations.INSTANCE.channelDetails(UUIDSerializerKt.toUUID(response.getId()), UUIDSerializerKt.toUUID(response.getChannelId()), ModelCompat.asSdk(response)));
                            } else {
                                navigationRepository.navigate(Destinations.INSTANCE.itemDetails(UUIDSerializerKt.toUUID(response.getId())));
                            }
                        }
                    }

                    @Override
                    public void onError(Exception exception) {
                        Timber.e(exception, "Error retrieving full object");
                        exception.printStackTrace();
                    }
                });
                break;
            case LiveTvProgram:
                org.jellyfin.sdk.model.api.BaseItemDto program = rowItem.getBaseItem();
                switch (rowItem.getSelectAction()) {

                    case ShowDetails:
                        navigationRepository.navigate(Destinations.INSTANCE.channelDetails(program.getId(), program.getChannelId(), program));
                        break;
                    case Play:
                        if (program.getPlayAccess() == org.jellyfin.sdk.model.api.PlayAccess.FULL) {
                            //Just play it directly - need to retrieve program channel via items api to convert to BaseItem
                            KoinJavaComponent.<ApiClient>get(ApiClient.class).GetItemAsync(program.getChannelId().toString(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                                @Override
                                public void onResponse(BaseItemDto response) {
                                    List<BaseItemDto> items = new ArrayList<>();
                                    items.add(response);
                                    Class newActivity = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class).getPlaybackActivityClass(ModelCompat.asSdk(response).getType());
                                    Intent intent = new Intent(activity, newActivity);
                                    KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentVideoQueue(JavaCompat.mapBaseItemCollection(items));
                                    intent.putExtra("Position", 0);
                                    activity.startActivity(intent);

                                }
                            });
                        } else {
                            Utils.showToast(activity, "Item not playable at this time");
                        }
                }
                break;

            case LiveTvChannel:
                //Just tune to it by playing
                final org.jellyfin.sdk.model.api.BaseItemDto channel = rowItem.getBaseItem();
                KoinJavaComponent.<ApiClient>get(ApiClient.class).GetItemAsync(channel.getId().toString(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                    @Override
                    public void onResponse(BaseItemDto response) {
                        PlaybackHelper.getItemsToPlay(ModelCompat.asSdk(response), false, false, new Response<List<org.jellyfin.sdk.model.api.BaseItemDto>>() {
                            @Override
                            public void onResponse(List<org.jellyfin.sdk.model.api.BaseItemDto> response) {
                                // TODO Check whether this usage of BaseItemType.valueOf is okay.
                                Class newActivity = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class).getPlaybackActivityClass(channel.getType());
                                Intent intent = new Intent(activity, newActivity);
                                KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentVideoQueue(response);
                                intent.putExtra("Position", 0);
                                activity.startActivity(intent);

                            }
                        });
                    }
                });
                break;

            case LiveTvRecording:
                switch (rowItem.getSelectAction()) {

                    case ShowDetails:
                        navigationRepository.navigate(Destinations.INSTANCE.itemDetails(rowItem.getBaseItem().getId()));
                        break;
                    case Play:
                        if (rowItem.getBaseItem().getPlayAccess() == PlayAccess.FULL) {
                            //Just play it directly but need to retrieve as base item
                            KoinJavaComponent.<ApiClient>get(ApiClient.class).GetItemAsync(rowItem.getBaseItem().getId().toString(), KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue().getId().toString(), new Response<BaseItemDto>() {
                                @Override
                                public void onResponse(BaseItemDto response) {
                                    Class newActivity = KoinJavaComponent.<PlaybackLauncher>get(PlaybackLauncher.class).getPlaybackActivityClass(rowItem.getBaseItemType());
                                    Intent intent = new Intent(activity, newActivity);
                                    List<BaseItemDto> items = new ArrayList<>();
                                    items.add(response);
                                    KoinJavaComponent.<MediaManager>get(MediaManager.class).setCurrentVideoQueue(JavaCompat.mapBaseItemCollection(items));
                                    intent.putExtra("Position", 0);
                                    activity.startActivity(intent);
                                }
                            });
                        } else {
                            Utils.showToast(activity, "Item not playable at this time");
                        }
                        break;
                }
                break;

            case SeriesTimer:
                navigationRepository.navigate(Destinations.INSTANCE.seriesTimerDetails(UUIDSerializerKt.toUUID(rowItem.getItemId()), ModelCompat.asSdk(rowItem.getSeriesTimerInfo())));
                break;


            case GridButton:
                switch (rowItem.getGridButton().getId()) {
                    case LiveTvOption.LIVE_TV_GUIDE_OPTION_ID:
                        navigationRepository.navigate(Destinations.INSTANCE.getLiveTvGuide());
                        break;

                    case LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID:
                        BaseItemDto folder = new BaseItemDto();
                        folder.setId(UUID.randomUUID().toString());
                        folder.setBaseItemType(BaseItemType.Folder);
                        folder.setName(activity.getString(R.string.lbl_recorded_tv));
                        navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(folder)));
                        break;

                    case LiveTvOption.LIVE_TV_SERIES_OPTION_ID:
                        BaseItemDto seriesTimers = new BaseItemDto();
                        seriesTimers.setId(UUID.randomUUID().toString());
                        seriesTimers.setBaseItemType(BaseItemType.Folder);
                        seriesTimers.setCollectionType("SeriesTimers");
                        seriesTimers.setName(activity.getString(R.string.lbl_series_recordings));
                        navigationRepository.navigate(Destinations.INSTANCE.libraryBrowser(ModelCompat.asSdk(seriesTimers)));
                        break;

                    case LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID:
                        navigationRepository.navigate(Destinations.INSTANCE.getLiveTvSchedule());
                        break;
                }
                break;
        }
    }
}
